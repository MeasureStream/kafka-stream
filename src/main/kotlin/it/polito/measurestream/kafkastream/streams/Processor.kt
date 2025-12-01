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
import org.springframework.beans.factory.annotation.Autowired
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
    fun ttnUplinkProcessor(builder: StreamsBuilder): KStream<Int, String> {
        val input: KStream<ByteArray, String> = builder.stream("ttn-uplink", Consumed.with(Serdes.ByteArray(), Serdes.String()))
        val processed: KStream<Int, String> =
            input.map { _, message ->

                val ttnmessage = decodeMessage(message)
                when (ttnmessage.fport) {
                    1 -> KeyValue(ttnmessage.fport, decodePayload1(ttnmessage.payload))
                    else -> KeyValue(ttnmessage.fport, ttnmessage.payload)
                }
            }
        processed
            .split()
            .branch(
                { key, _ -> key == 1 },
                Branched.withConsumer { ks ->
                    ks.to("ttn-uplink-measure", Produced.with(integerSerde, Serdes.String()))
                },
            ).branch(
                { key, _ -> key == 2 },
                Branched.withConsumer { ks ->
                    ks.to("ttn-uplink-command", Produced.with(integerSerde, Serdes.String()))
                },
            ).defaultBranch(
                Branched.withConsumer { ks ->
                    ks.to("ttn-uplink-error", Produced.with(integerSerde, Serdes.String()))
                },
            )
        return processed
    }

    private fun decodeMessage(message: String): TTNMessage {
        val trimmed = message.trim().removeSurrounding("\"")
        val decoded = Base64.getDecoder().decode(trimmed)
        try {
            val jsonStr = String(decoded)
            println("RAW MESSAGE: $jsonStr")
            val root: JsonNode = objectMapper.readTree(jsonStr)
            val frmPayload =
                root["uplink_message"]?.get("frm_payload")?.asText()
                    ?: throw Exception("Missing frm_payload in message")
            val fport = root["uplink_message"]?.get("f_port")?.asInt() ?: throw Exception("Missing f_port in the message")

            val devEui =
                root["data"]?.get("end_device_ids")?.get("dev_eui")?.asText()
                    ?: root["identifiers"]
                        ?.get(0)
                        ?.get("device_ids")
                        ?.get("dev_eui")
                        ?.asText()
            // root.get("dev_eui")?.asText()
            println("This is the dev_eui: $devEui")

            // beware that frmPayload is encoded
            return TTNMessage(fport, frmPayload)
        } catch (e: Exception) {
            println("Error parsing message: $message")
            e.printStackTrace()
            throw e
        }
    }

    private fun decodePayload1(frmPayload: String): String {
        val bytes = Base64.getDecoder().decode(frmPayload)

        if (bytes.size < 9) { // 6 + 1 + 2
            println("Payload too short: ${bytes.size} bytes")
            return ""
        }

        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)

        // 1) MAC address (6 byte)
        val macBytes = ByteArray(6)
        buffer.get(macBytes)
        val mac = macBytes.joinToString(":") { "%02X".format(it) }

        // 2) RSSI (1 byte)
        val rssi = buffer.get().toInt()

        // 3) Value (2 byte)
        // val value = buffer.short.toDouble()
        // // byte 7 = LSB, byte 8 = MSB (little-endian)
        val lsb = bytes[7].toInt() and 0xFF
        val msb = bytes[8].toInt() and 0xFF

        // ricostruisci int16 signed
        val raw = (msb shl 8) or lsb
        val tempInt = if (raw and 0x8000 != 0) raw or -0x10000 else raw

        val temperature = tempInt.toDouble() / 100.0

        val m =
            MeasureDecoded(
                value = temperature,
                unit = decodeUnit(1),
                nodeId = 1,
                time = Instant.now().toString(),
            )

        return Json.encodeToString<MeasureDecoded>(m)
    }

    private fun decodePayload(frmPayload: String): String {
        val bytes = Base64.getDecoder().decode(frmPayload)

        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)

        // 1) value (4 bytes)
        val valueFloat = buffer.float

        // 2) unit (2 bytes → codice)
        val unitCode = buffer.short.toInt() // esempio: 1=°C, 2=%, etc.
        val unit = decodeUnit(unitCode)

        // 3) nodeId (4 bytes)
        val nodeId = buffer.int.toLong()

        val m =
            MeasureDecoded(
                value = valueFloat.toDouble(),
                unit = unit,
                nodeId = nodeId,
                time = Instant.now().toString(),
            )

        return Json.encodeToString<MeasureDecoded>(m)
    }

    private fun decodeUnit(code: Int): String =
        when (code) {
            1 -> "°C"
            2 -> "%"
            3 -> "Pa"
            else -> "unknown"
        }
}
