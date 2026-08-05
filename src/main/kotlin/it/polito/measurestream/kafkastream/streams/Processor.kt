package it.polito.measurestream.kafkastream.streams

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.measurestream.kafkastream.dto.MeasureDecoded
import it.polito.measurestream.kafkastream.dto.TTNMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64

@Component
class TTNStream(
    private val objectMapper: ObjectMapper,
    private val integerSerde: Serde<Int>,
    private val stringSerde: Serde<String>,
) {
    private val log = LoggerFactory.getLogger(TTNStream::class.java)

    fun ttnUplinkProcessor(builder: StreamsBuilder): KStream<Int, String> {
        val input: KStream<ByteArray, String> = builder.stream(
            "ttn-uplink", 
            Consumed.with(Serdes.ByteArray(), Serdes.String())
        )

        val decodedStream = input.map { _, message ->
            try {
                val ttnMessage = decodeMessage(message)
                KeyValue(ttnMessage.fport, ttnMessage)
            } catch (e: Exception) {
                log.error("[STREAM PARSE ERROR] Impossibile decodificare il messaggio TTN grezzo: {}", e.message)
                log.debug("[RAW PAYLOAD FAILED]: {}", message)
                null
            }
        }.filter { _, v -> v != null }.mapValues { _, v -> v!! }

        // Pipeline per la qualità del segnale
        decodedStream.mapValues { ttnMessage ->
            val signalInfo = mapOf(
                "devEUI" to ttnMessage.devEUI,
                "deviceId" to ttnMessage.deviceId,
                "rssi" to ttnMessage.LoRarssi,
                "dataRate" to ttnMessage.dataRate,
                "airtime" to ttnMessage.consumedAirtime,
                "time" to ttnMessage.time,
                "spreadingFactor" to ttnMessage.spreadingFactor,
                "bandwidth" to ttnMessage.bandwidth,
                "fCnt" to ttnMessage.fCnt
            )
            objectMapper.writeValueAsString(signalInfo)
        }.to("ttn-uplink-signal-quality", Produced.with(integerSerde, Serdes.String()))

        // Pipeline per la decodifica specifica per FPort
        val processed: KStream<Int, String> = decodedStream.mapValues { ttnMessage ->
            try {
                when (ttnMessage.fport) {
                    1 -> decodePayload1(ttnMessage.payload, ttnMessage.devEUI, ttnMessage.time, ttnMessage.LoRarssi)
                    10 -> decodePayload10(ttnMessage.payload, ttnMessage.devEUI, ttnMessage.deviceId)
                    16 -> decodePayload16(ttnMessage.payload, ttnMessage.devEUI, ttnMessage.deviceId)
                    else -> {
                        log.warn("[UNHANDLED FPORT] Nessun decoder registrato per f_port={}", ttnMessage.fport)
                        ttnMessage.payload
                    }
                }
            } catch (e: Exception) {
                log.error("[DECODER ERROR] Fallita decodifica payload per f_port={} DevEUI={}: {}", 
                    ttnMessage.fport, ttnMessage.devEUI, e.message, e)
                ""
            }
        }.filter { _, value -> value != null && value.isNotBlank() }

        // Partizionamento sui topic Kafka
        processed
            .split()
            .branch({ key, _ -> key == 1 }, Branched.withConsumer { ks ->
                ks.to("ttn-uplink-measure", Produced.with(integerSerde, Serdes.String()))
            })
            .branch({ key, _ -> key == 2 }, Branched.withConsumer { ks ->
                ks.to("ttn-uplink-command", Produced.with(integerSerde, Serdes.String()))
            })
            .branch({ key, _ -> key == 3 }, Branched.withConsumer { ks ->
                ks.to("mu-registration", Produced.with(integerSerde, Serdes.String()))
            })
            .branch({ key, _ -> key == 10 }, Branched.withConsumer { ks ->
                ks.to("cu-status", Produced.with(integerSerde, Serdes.String()))
            })
            .branch({ key, _ -> key == 16 }, Branched.withConsumer { ks ->
                ks.to("cu-join-notification", Produced.with(integerSerde, Serdes.String()))
            })
            .defaultBranch(Branched.withConsumer { ks ->
                ks.to("ttn-uplink-error", Produced.with(integerSerde, Serdes.String()))
            })

        return processed
    }

    private fun decodeMessage(message: String): TTNMessage {
        val trimmed = message.trim().removeSurrounding("\"")
        
        val jsonBytes = try {
            Base64.getDecoder().decode(trimmed)
        } catch (e: Exception) {
            log.error("[DECODE ERROR] Fallimento decodifica Base64 dell'intero messaggio Kafka")
            throw IllegalArgumentException("Base64 wrapper invalido")
        }

        val jsonStr = String(jsonBytes)
        val root: JsonNode = try {
            objectMapper.readTree(jsonStr)
        } catch (e: Exception) {
            log.error("[DECODE ERROR] Impossibile formattare il payload come JSON: {}", jsonStr)
            throw IllegalArgumentException("JSON malformato")
        }

        val uplink = root["uplink_message"] 
            ?: throw Exception("Campo 'uplink_message' assente nel JSON")

        val frmPayload = uplink["frm_payload"]?.asText() 
            ?: throw Exception("Campo 'frm_payload' assente in uplink_message")

        val fport = uplink["f_port"]?.asInt() 
            ?: throw Exception("Campo 'f_port' assente in uplink_message")

        val fCnt = uplink["f_cnt"]?.asInt() ?: 0

        val time = root["received_at"]?.asText()
            ?: uplink["received_at"]?.asText()
            ?: uplink["settings"]?.get("time")?.asText()
            ?: run {
                log.warn("[DECODE WARNING] Campo 'received_at'/'time' non trovato nel JSON. Uso il timestamp corrente.")
                Instant.now().toString()
            }

        val rssi: Int = uplink["rx_metadata"]?.get(0)?.get("rssi")?.asInt() 
            ?: run {
                log.warn("[DECODE WARNING] Campo 'rssi' non presente in rx_metadata[0]. Default a -100")
                -100
            }

        val devEui = root["end_device_ids"]?.get("dev_eui")?.asText()
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

        log.debug("[TTN PARSE SUCCESS] DeviceId={}, DevEUI={}, FPort={}, FrameCnt={}", deviceId, devEui, fport, fCnt)

        return TTNMessage(fport, frmPayload, deviceId, devEui, time, rssi, sf, bw, dataRate, airtime, fCnt)
    }

    private fun decodePayload1(frmPayload: String, devEUI: String, time: String, LoRarssi: Int): String {
        val bytes = decodeBase64Payload(frmPayload, 1) ?: return ""

        if (bytes.size < 7) {
            log.warn("[FPORT 1 WARNING] Payload troppo corto: ricevuti {} byte, richiesti 7", bytes.size)
            return ""
        }

        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        val muid = buffer.int.toLong() and 0xFFFFFFFFL
        val rssi = buffer.get().toInt()

        val lsb = bytes[5].toInt() and 0xFF
        val msb = bytes[6].toInt() and 0xFF
        val raw = (msb shl 8) or lsb
        val tempInt = if (raw and 0x8000 != 0) raw or -0x10000 else raw
        val temperature = tempInt.toDouble() / 100.0

        log.info("[FPORT 1 SUCCESS] MUID={} | Temp={}°C | RSSI={}", muid, temperature, rssi)

        val m = MeasureDecoded(
            value = temperature,
            unit = "°C",
            nodeId = muid,
            time = time,
            rssi = rssi,
            devEUI = devEUI,
            LoRarssi = LoRarssi
        )

        return Json.encodeToString(m)
    }

    private fun decodePayload10(frmPayload: String, devEUI: String, deviceId: String): String {
        val bytes = decodeBase64Payload(frmPayload, 10) ?: return ""

        if (bytes.size < 4) {
            log.warn("[FPORT 10 WARNING] Payload troppo corto: ricevuti {} byte, richiesti almeno 4", bytes.size)
            return ""
        }

        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        val model = buffer.short.toInt() and 0xFFFF
        val rawbattery = buffer.get().toInt() and 0xFF
        val isCharging = rawbattery == 255
        val battery = if (isCharging) 100 else ((rawbattery.toDouble() / 254.0) * 100.0).toInt()
        val ptx = buffer.get().toInt() and 0xFF
        val acPowered = rawbattery == 254
        val statusRaw = if (buffer.remaining() >= 1) buffer.get().toInt() and 0xFF else 0

        val devEuiLong = parseDevEuiToLong(devEUI)

        val update = mapOf(
            "devEui" to devEuiLong,
            "deviceId" to deviceId,
            "model" to model,
            "batteryLevel" to battery,
            "ptx" to ptx,
            "acPowered" to acPowered,
            "isCharging" to isCharging,
            "statusRaw" to statusRaw
        )

        log.info("[FPORT 10 SUCCESS] DevEUI={} ({}), Model={}, Bat={}%", deviceId, devEuiLong, model, battery)
        return objectMapper.writeValueAsString(update)
    }

    private fun decodePayload16(frmPayload: String, devEUI: String, deviceId: String): String {
        val bytes = decodeBase64Payload(frmPayload, 16) ?: return ""

        if (bytes.size < 4) {
            log.warn("[FPORT 16 WARNING] Payload troppo corto: ricevuti {} byte, richiesti almeno 4", bytes.size)
            return ""
        }

        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        val muList = mutableListOf<Map<String, Any>>()
        var localIdIndex = 1

        while (buffer.remaining() >= 4) {
            val extendedId = buffer.int
            val localId = localIdIndex++
            val model = (extendedId ushr 16) and 0xFFFF

            muList.add(
                mapOf(
                    "extendedId" to extendedId,
                    "localId" to localId,
                    "model" to model
                )
            )
        }

        val devEuiLong = parseDevEuiToLong(devEUI)

        val joinNotification = mapOf(
            "devEui" to devEuiLong,
            "deviceId" to deviceId,
            "muList" to muList
        )

        log.info("[FPORT 16 SUCCESS] DevEUI={} ({}), MU Trovate={}", deviceId, devEuiLong, muList.size)
        return objectMapper.writeValueAsString(joinNotification)
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
            log.error("[ERROR] Impossibile convertire DevEUI Hex '{}' in Unsigned Long: {}", devEUI, e.message)
            0L
        }
    }

    private fun calculateDataRate(sf: Int, bw: Long): String {
        return when {
            sf == 12 && bw == 125000L -> "DR0"
            sf == 11 && bw == 125000L -> "DR1"
            sf == 10 && bw == 125000L -> "DR2"
            sf == 9  && bw == 125000L -> "DR3"
            sf == 8  && bw == 125000L -> "DR4"
            sf == 7  && bw == 125000L -> "DR5"
            sf == 7  && bw == 250000L -> "DR6"
            else -> "DR_INVALID_OR_OVERSIZE"
        }
    }
}
