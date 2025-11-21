package it.polito.measurestream.kafkastream.streams
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.measurestream.kafkastream.dto.MeasureDecoded
import it.polito.measurestream.kafkastream.dto.TTNMessage
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Branched
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
    @Autowired
    private val integerSerde: Serdes.IntegerSerde,
) {
    fun ttnUplinkProcessor(builder: StreamsBuilder): KStream<Int, String> {
        val input: KStream<ByteArray, String> = builder.stream("ttn-uplink")
        val processed: KStream<Int, String> =
            input.map { _, message ->

                val ttnmessage = decodeMessage(message)
                when (ttnmessage.fport) {
                    1 -> KeyValue(ttnmessage.fport, decodePayload(ttnmessage.payload))
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
        /*
        val output =
            input.mapValues { message ->
                val trimmed = message.trim().removeSurrounding("\"")
                val decoded = Base64.getDecoder().decode(trimmed)
                try {
                    val jsonStr = String(decoded)
                    println("decoded message:  $jsonStr")
                    val root: JsonNode = objectMapper.readTree(jsonStr)
                    val frmPayload =
                        root["uplink_message"]?.get("frm_payload")?.asText()
                            ?: throw Exception("Missing frm_payload in message")
                    // Decode Base64
                    // Convert decoded bytes → Measure object
                    val fport = root["uplink_message"]?.get("f_port")?.asInt()

                    val bytes = Base64.getDecoder().decode(frmPayload)
                    when (fport) {
                        1 -> decodePayload(bytes).toString()
                        else -> throw Error("fport code not provided $fport")
                    }
                    // val decoded = decodePayload(bytes)
                    // decoded.toString()
                } catch (e: Exception) {
                    println("Error parsing message: $message")
                    e.printStackTrace()
                    message
                }
            }
         */

        // output.to("ttn-uplink-clean")
        // return output
    }

    private fun decodeMessage(message: String): TTNMessage {
        val trimmed = message.trim().removeSurrounding("\"")
        val decoded = Base64.getDecoder().decode(trimmed)
        try {
            val jsonStr = String(decoded)
            val root: JsonNode = objectMapper.readTree(jsonStr)
            val frmPayload =
                root["uplink_message"]?.get("frm_payload")?.asText()
                    ?: throw Exception("Missing frm_payload in message")
            val fport = root["uplink_message"]?.get("f_port")?.asInt() ?: throw Exception("Missing f_port in the message")

            // beware that frmPayload is encoded
            return TTNMessage(fport, frmPayload)
        } catch (e: Exception) {
            println("Error parsing message: $message")
            e.printStackTrace()
            throw e
        }
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

        return MeasureDecoded(
            value = valueFloat.toDouble(),
            unit = unit,
            nodeId = nodeId,
            time = Instant.now(),
        ).toString()
    }

    private fun decodeUnit(code: Int): String =
        when (code) {
            1 -> "°C"
            2 -> "%"
            3 -> "Pa"
            else -> "unknown"
        }
}
