package it.polito.measurestream.kafkastream.streams

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.measurestream.kafkastream.dto.TTNMessage
import java.time.Instant
import java.util.Base64
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Produced
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Da TTN a una busta normalizzata: **qui non si interpreta nessun payload**.
 *
 * Questo servizio fa una cosa sola: toglie il messaggio dalla busta JSON di TTN, ne ricava
 * chi l'ha mandato, su quale porta e quando, e lo rimette in coda con i byte intatti. Cosa
 * significhino quei byte lo sa solo sensor-manager, perche' per saperlo servono i template, la
 * topologia della CU e l'istantanea della configurazione attiva: tre cose che stanno li' e che
 * li' si aggiornano nella stessa transazione.
 *
 * Il guadagno non e' estetico. Prima ogni porta aveva il suo decoder qui e il suo topic, e un
 * formato nuovo richiedeva di rilasciare due servizi insieme; inoltre la chiave dei messaggi
 * era la porta, quindi la notifica di una MU e il report che la seguiva potevano finire su
 * partizioni diverse e arrivare in ordine invertito. Con la chiave DevEUI, tutto cio' che una
 * CU manda resta in fila.
 */
@Component
class TTNStream(
        private val objectMapper: ObjectMapper,
        private val stringSerde: Serde<String>,
) {
  private val log = LoggerFactory.getLogger(TTNStream::class.java)

  fun ttnUplinkProcessor(builder: StreamsBuilder): KStream<String, String> {
    val input: KStream<ByteArray, String> =
            builder.stream("ttn-uplink", Consumed.with(Serdes.ByteArray(), Serdes.String()))

    // Chiave = DevEUI: tutti i messaggi di una CU finiscono sulla stessa partizione e restano
    // in ordine, quindi una notifica MU e' sempre elaborata prima del report che la segue.
    // flatMap e non map: un messaggio illeggibile viene scartato, invece di produrre un
    // KeyValue null che fermerebbe il thread dello stream.
    val decodedStream: KStream<String, TTNMessage> =
            input.flatMap<String, TTNMessage> { _, message ->
              try {
                val ttnMessage = decodeMessage(message)
                listOf(KeyValue(ttnMessage.devEUI, ttnMessage))
              } catch (e: Exception) {
                log.error(
                        "[STREAM PARSE ERROR] Impossibile decodificare il messaggio TTN grezzo: {}",
                        e.message
                )
                log.debug("[RAW PAYLOAD FAILED]: {}", message)
                emptyList<KeyValue<String, TTNMessage>>()
              }
            }

    // Qualita' del segnale: sono metadati della rete, non un payload, e restano su un topic
    // proprio perche' riguardano ogni messaggio qualunque sia la porta.
    decodedStream
            .mapValues { ttnMessage ->
              val signalInfo =
                      mapOf(
                              "devEUI" to ttnMessage.devEUI,
                              "deviceId" to ttnMessage.deviceId,
                              "rssi" to ttnMessage.LoRarssi,
                              "snr" to ttnMessage.snr,
                              "dataRate" to ttnMessage.dataRate,
                              "airtime" to ttnMessage.consumedAirtime,
                              "time" to ttnMessage.time,
                              "spreadingFactor" to ttnMessage.spreadingFactor,
                              "bandwidth" to ttnMessage.bandwidth,
                              "fCnt" to ttnMessage.fCnt
                      )
              objectMapper.writeValueAsString(signalInfo)
            }
            .to("ttn-uplink-signal-quality", Produced.with(stringSerde, Serdes.String()))

    // Un topic solo per tutte le porte: la porta viaggia dentro la busta, non nel nome del
    // topic. Una porta nuova non richiede piu' di toccare questo servizio.
    val uplinks: KStream<String, String> = decodedStream.mapValues { it -> envelope(it) }
    uplinks.to("lora-uplink", Produced.with(stringSerde, Serdes.String()))

    return uplinks
  }

  /**
   * La busta: chi, quando, su quale porta, e i byte esattamente come sono arrivati. Il
   * payload resta in base64 e non viene toccato: qualunque tentativo di interpretarlo qui
   * sarebbe una seconda verita' accanto a quella dei template.
   */
  private fun envelope(message: TTNMessage): String {
    val devEuiLong = parseDevEuiToLong(message.devEUI)
    val body =
            mapOf(
                    "devEui" to devEuiLong,
                    "deviceId" to message.deviceId,
                    "fport" to message.fport,
                    "timestamp" to message.time,
                    "rawPayload" to message.payload,
                    "fCnt" to message.fCnt,
            )
    log.info(
            "[FPORT 0x{}] DevEUI={} ({}) f_cnt={}: inoltrato a sensor-manager",
            "%02X".format(message.fport),
            message.deviceId,
            devEuiLong,
            message.fCnt,
    )
    return objectMapper.writeValueAsString(body)
  }

  private fun decodeMessage(message: String): TTNMessage {
    val trimmed = message.trim().removeSurrounding("\"")

    val jsonBytes =
            try {
              Base64.getDecoder().decode(trimmed)
            } catch (e: Exception) {
              log.error("[DECODE ERROR] Fallimento decodifica Base64 dell'intero messaggio Kafka")
              throw IllegalArgumentException("Base64 wrapper invalido")
            }

    val jsonStr = String(jsonBytes)
    val root: JsonNode =
            try {
              objectMapper.readTree(jsonStr)
            } catch (e: Exception) {
              log.error("[DECODE ERROR] Impossibile formattare il payload come JSON: {}", jsonStr)
              throw IllegalArgumentException("JSON malformato")
            }

    val uplink =
            root["uplink_message"] ?: throw Exception("Campo 'uplink_message' assente nel JSON")

    val frmPayload =
            uplink["frm_payload"]?.asText()
                    ?: throw Exception("Campo 'frm_payload' assente in uplink_message")

    val fport =
            uplink["f_port"]?.asInt() ?: throw Exception("Campo 'f_port' assente in uplink_message")

    val fCnt = uplink["f_cnt"]?.asInt() ?: 0

    val time =
            root["received_at"]?.asText()
                    ?: uplink["received_at"]?.asText() ?: uplink["settings"]?.get("time")?.asText()
                            ?: run {
                      log.warn(
                              "[DECODE WARNING] Campo 'received_at'/'time' non trovato nel JSON. Uso il timestamp corrente."
                      )
                      Instant.now().toString()
                    }

    val rxMetadata = uplink["rx_metadata"]?.get(0)

    val rssi: Int =
            rxMetadata?.get("rssi")?.asInt()
                    ?: run {
                      log.warn(
                              "[DECODE WARNING] Campo 'rssi' non presente in rx_metadata[0]. Default a -100"
                      )
                      -100
                    }

    val snr: Double =
            rxMetadata?.get("snr")?.asDouble()
                    ?: run {
                      log.warn(
                              "[DECODE WARNING] Campo 'snr' non presente in rx_metadata[0]. Default a 0.0"
                      )
                      0.0
                    }
    val devEui =
            root["end_device_ids"]?.get("dev_eui")?.asText()
                    ?: root["identifiers"]?.get(0)?.get("device_ids")?.get("dev_eui")?.asText()
                            ?: "NOT FOUND"

    if (devEui == "NOT FOUND") {
      log.warn("[DECODE WARNING] 'dev_eui' non trovato nell'oggetto JSON")
    }

    val settings = uplink["settings"]
    val dataRateModulation = settings?.get("data_rate")?.get("lora")

    val sf = dataRateModulation?.get("spreading_factor")?.asInt() ?: 0
    val bw = dataRateModulation?.get("bandwidth")?.asLong() ?: 0L
    val airtime = uplink["consumed_airtime"]?.asText() ?: "0s"
    val dataRate = calculateDataRate(sf, bw)

    val deviceIds = root["end_device_ids"]
    val deviceId = deviceIds?.get("device_id")?.asText() ?: "UNKNOWN_DEVICE"

    log.debug(
            "[TTN PARSE SUCCESS] DeviceId={}, DevEUI={}, FPort={}, FrameCnt={}",
            deviceId,
            devEui,
            fport,
            fCnt
    )

    return TTNMessage(
            fport = fport,
            payload = frmPayload,
            deviceId = deviceId,
            devEUI = devEui,
            time = time,
            LoRarssi = rssi,
            snr = snr,
            spreadingFactor = sf,
            bandwidth = bw,
            dataRate = dataRate,
            consumedAirtime = airtime,
            fCnt = fCnt
    )
  }

  private fun parseDevEuiToLong(devEUI: String): Long {
    return try {
      java.lang.Long.parseUnsignedLong(devEUI.trim(), 16)
    } catch (e: Exception) {
      log.error(
              "[ERROR] Impossibile convertire DevEUI Hex '{}' in Unsigned Long: {}",
              devEUI,
              e.message
      )
      0L
    }
  }

  private fun calculateDataRate(sf: Int, bw: Long): String {
    return when {
      sf == 12 && bw == 125000L -> "DR0"
      sf == 11 && bw == 125000L -> "DR1"
      sf == 10 && bw == 125000L -> "DR2"
      sf == 9 && bw == 125000L -> "DR3"
      sf == 8 && bw == 125000L -> "DR4"
      sf == 7 && bw == 125000L -> "DR5"
      sf == 7 && bw == 250000L -> "DR6"
      else -> "DR_INVALID_OR_OVERSIZE"
    }
  }
}
