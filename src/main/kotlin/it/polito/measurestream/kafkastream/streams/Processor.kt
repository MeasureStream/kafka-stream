package it.polito.measurestream.kafkastream.streams

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.measurestream.kafkastream.dto.TTNMessage
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Branched
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Produced
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Payload decodificato insieme alla FPort da cui arriva, per instradarlo sul topic giusto. */
data class DecodedUplink(val fport: Int, val payload: String)

@Component
class TTNStream(
        private val objectMapper: ObjectMapper,
        private val stringSerde: Serde<String>,
) {
  private val log = LoggerFactory.getLogger(TTNStream::class.java)

  fun ttnUplinkProcessor(builder: StreamsBuilder): KStream<String, DecodedUplink> {
    val input: KStream<ByteArray, String> =
            builder.stream("ttn-uplink", Consumed.with(Serdes.ByteArray(), Serdes.String()))

    // Chiave = DevEUI: tutti i messaggi di una CU finiscono sulla stessa partizione e restano
    // in ordine, quindi una notifica MU è sempre elaborata prima del report che la segue.
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

    // Pipeline per la qualità del segnale
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

    // Pipeline per la decodifica specifica per FPort: la FPort viaggia nel valore,
    // perché la chiave ora è il DevEUI
    val processed: KStream<String, DecodedUplink> =
            decodedStream
                    .mapValues { ttnMessage -> DecodedUplink(ttnMessage.fport, decodeByFPort(ttnMessage)) }
                    .filter { _, value -> value.payload.isNotBlank() }

    processed
            .split()
            // La FPort 1 (misura singola verso measure-manager, dismesso) non ha più un topic:
            // eventuali messaggi finiscono in ttn-uplink-error.
            .branch({ _, v -> v.fport == 2 }, Branched.withConsumer { ks -> ks.toTopic("ttn-uplink-command") })
            .branch({ _, v -> v.fport == 3 }, Branched.withConsumer { ks -> ks.toTopic("mu-registration") })
            .branch({ _, v -> v.fport == 10 }, Branched.withConsumer { ks -> ks.toTopic("cu-status") })
            .branch({ _, v -> v.fport == 11 }, Branched.withConsumer { ks -> ks.toTopic("cu-status-version") })
            .branch({ _, v -> v.fport == 16 }, Branched.withConsumer { ks -> ks.toTopic("cu-join-notification") })
            .branch({ _, v -> v.fport == 33 }, Branched.withConsumer { ks -> ks.toTopic("cu-measures") })
            .branch({ _, v -> v.fport == 49 }, Branched.withConsumer { ks -> ks.toTopic("cu-measures-extra") })
            .defaultBranch(Branched.withConsumer { ks -> ks.toTopic("ttn-uplink-error") })

    return processed
  }

  /** Scrive sul topic il solo payload, con chiave DevEUI. */
  private fun KStream<String, DecodedUplink>.toTopic(topic: String) =
          mapValues { value -> value.payload }.to(topic, Produced.with(stringSerde, Serdes.String()))

  /** Decodifica il payload secondo la FPort; stringa vuota se la decodifica fallisce. */
  private fun decodeByFPort(ttnMessage: TTNMessage): String =
          try {
            when (ttnMessage.fport) {
              10 -> decodePayload10(ttnMessage.payload, ttnMessage.devEUI, ttnMessage.deviceId)
              11 -> decodePayload11(ttnMessage.payload, ttnMessage.devEUI, ttnMessage.deviceId)
              16 -> decodePayload16(ttnMessage.payload, ttnMessage.devEUI, ttnMessage.deviceId)
              33 ->
                      decodePayload33(
                              ttnMessage.payload,
                              ttnMessage.devEUI,
                              ttnMessage.deviceId,
                              ttnMessage.time
                      )
              49 ->
                      decodePayload49(
                              ttnMessage.payload,
                              ttnMessage.devEUI,
                              ttnMessage.deviceId,
                              ttnMessage.time
                      )
              else -> {
                log.warn(
                        "[UNHANDLED FPORT] Nessun decoder registrato per f_port={}",
                        ttnMessage.fport
                )
                ttnMessage.payload
              }
            }
          } catch (e: Exception) {
            log.error(
                    "[DECODER ERROR] Fallita decodifica payload per f_port={} DevEUI={}: {}",
                    ttnMessage.fport,
                    ttnMessage.devEUI,
                    e.message,
                    e
            )
            ""
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

  private fun decodePayload10(frmPayload: String, devEUI: String, deviceId: String): String {
    val bytes = decodeBase64Payload(frmPayload, 10) ?: return ""

    if (bytes.size < 4) {
      log.warn(
              "[FPORT 10 WARNING] Payload troppo corto: ricevuti {} byte, richiesti almeno 4",
              bytes.size
      )
      return ""
    }

    val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
    val model = buffer.short.toInt() and 0xFFFF
    val rawbattery = buffer.get().toInt() and 0xFF
    val isCharging = rawbattery == 255
    val acPowered = rawbattery == 254
    val battery =
            if (isCharging || acPowered) 100
            else (rawbattery.toDouble()).toInt() // TODO da fare 100 +  in carica
    val ptx = buffer.get().toInt() and 0xFF
    val statusRaw = if (buffer.remaining() >= 1) buffer.get().toInt() and 0xFF else 0

    val devEuiLong = parseDevEuiToLong(devEUI)

    val update =
            mapOf(
                    "devEui" to devEuiLong,
                    "deviceId" to deviceId,
                    "model" to model,
                    "batteryLevel" to battery,
                    "ptx" to ptx,
                    "acPowered" to acPowered,
                    "isCharging" to isCharging,
                    "statusRaw" to statusRaw
            )

    log.info(
            "[FPORT 10 SUCCESS] DevEUI={} ({}), Model={}, Bat={}%",
            deviceId,
            devEuiLong,
            model,
            battery
    )
    return objectMapper.writeValueAsString(update)
  }

  private fun decodePayload11(frmPayload: String, devEUI: String, deviceId: String): String {
    val bytes = decodeBase64Payload(frmPayload, 10) ?: return ""

    if (bytes.size < 4) {
      log.warn(
              "[FPORT 11 WARNING] Payload troppo corto: ricevuti {} byte, richiesti almeno 4",
              bytes.size
      )
      return ""
    }

    val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
    val model = buffer.short.toInt() and 0xFFFF
    val configVersion = buffer.get().toInt() and 0xFF
    val templateVersion = buffer.get().toInt() and 0xFF
    val rawbattery = buffer.get().toInt() and 0xFF
    val isCharging = rawbattery == 255
    val acPowered = rawbattery == 254
    val battery =
            if (isCharging || acPowered) 100
            else (rawbattery.toDouble()).toInt() // TODO da fare 100 +  in carica
    val ptx = buffer.get().toInt() and 0xFF
    val statusRaw = if (buffer.remaining() >= 1) buffer.get().toInt() and 0xFF else 0

    val devEuiLong = parseDevEuiToLong(devEUI)

    val update =
            mapOf(
                    "devEui" to devEuiLong,
                    "deviceId" to deviceId,
                    "configVersion" to configVersion,
                    "templateVersion" to templateVersion,
                    "model" to model,
                    "batteryLevel" to battery,
                    "ptx" to ptx,
                    "acPowered" to acPowered,
                    "isCharging" to isCharging,
                    "statusRaw" to statusRaw
            )

    log.info(
            "[FPORT 11 SUCCESS] DevEUI={} ({}), configVersion={}, templateVersion={}, Model={}, Bat={}%",
            deviceId,
            devEuiLong,
            configVersion,
            templateVersion,
            model,
            battery
    )
    return objectMapper.writeValueAsString(update)
  }

  private fun decodePayload16(frmPayload: String, devEUI: String, deviceId: String): String {
    val bytes = decodeBase64Payload(frmPayload, 16) ?: return ""

    if (bytes.size < 4) {
      log.warn(
              "[FPORT 16 WARNING] Payload troppo corto: ricevuti {} byte, richiesti almeno 4",
              bytes.size
      )
      return ""
    }

    val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
    val muList = mutableListOf<Map<String, Any>>()
    var localIdIndex = 1

    while (buffer.remaining() >= 4) {
      val extendedId = buffer.int
      val localId = localIdIndex++
      val model = (extendedId ushr 16) and 0xFFFF

      muList.add(mapOf("extendedId" to extendedId, "localId" to localId, "model" to model))
    }

    val devEuiLong = parseDevEuiToLong(devEUI)

    val joinNotification = mapOf("devEui" to devEuiLong, "deviceId" to deviceId, "muList" to muList)

    log.info("[FPORT 16 SUCCESS] DevEUI={} ({}), MU Trovate={}", deviceId, devEuiLong, muList.size)
    return objectMapper.writeValueAsString(joinNotification)
  }

  private fun decodePayload33(
          frmPayload: String,
          devEUI: String,
          deviceId: String,
          timeISO: String
  ): String {
    val bytes = decodeBase64Payload(frmPayload, 33) ?: return ""

    if (bytes.isEmpty()) {
      log.warn("[FPORT 33 WARNING] Payload vuoto per DevEUI={}", devEUI)
      return ""
    }

    val buffer = ByteBuffer.wrap(bytes)

    val configVersion = buffer.get().toInt() and 0xFF

    val remainingBytes = ByteArray(buffer.remaining())
    buffer.get(remainingBytes)

    val devEuiLong = parseDevEuiToLong(devEUI)

    val configNotification =
            mapOf(
                    "devEui" to devEuiLong,
                    "deviceId" to deviceId,
                    "configVersion" to configVersion,
                    "timestamp" to timeISO,
                    "rawPayload" to Base64.getEncoder().encodeToString(remainingBytes)
            )

    log.info(
            "[FPORT 33 SUCCESS] DevEUI={} ({}), ConfigVersion={}",
            deviceId,
            devEuiLong,
            configVersion
    )
    return objectMapper.writeValueAsString(configNotification)
  }

  private fun decodePayload49(
          frmPayload: String,
          devEUI: String,
          deviceId: String,
          timeISO: String
  ): String {
    val bytes = decodeBase64Payload(frmPayload, 49) ?: return ""

    if (bytes.isEmpty()) {
      log.warn("[FPORT 49 WARNING] Payload vuoto per DevEUI={}", devEUI)
      return ""
    }

    val buffer = ByteBuffer.wrap(bytes)

    val configVersion = buffer.get().toInt() and 0xFF

    val remainingBytes = ByteArray(buffer.remaining())
    buffer.get(remainingBytes)

    val devEuiLong = parseDevEuiToLong(devEUI)

    val configNotification =
            mapOf(
                    "devEui" to devEuiLong,
                    "deviceId" to deviceId,
                    "configVersion" to configVersion,
                    "timestamp" to timeISO,
                    "rawPayload" to Base64.getEncoder().encodeToString(remainingBytes)
            )

    log.info(
            "[FPORT 49 SUCCESS] DevEUI={} ({}), ConfigVersion={}",
            deviceId,
            devEuiLong,
            configVersion
    )
    return objectMapper.writeValueAsString(configNotification)
  }

  private fun decodeBase64Payload(frmPayload: String, fport: Int): ByteArray? {
    return try {
      Base64.getDecoder().decode(frmPayload)
    } catch (e: Exception) {
      log.error("[FPORT {} ERROR] frm_payload non e' un Base64 valido: '{}'", fport, frmPayload)
      null
    }
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
