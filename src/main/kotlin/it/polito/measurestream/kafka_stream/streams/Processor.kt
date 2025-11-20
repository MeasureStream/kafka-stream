package it.polito.measurestream.kafka_stream.streams
import it.polito.measurestream.kafka_stream.dto.MeasureDecoded
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.KStream
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64

class TTNStream {
    fun ttnUplinkProcessor(builder: StreamsBuilder): KStream<String, String> {
        val input: KStream<String, String> = builder.stream("ttn-uplink")
        val output =
            input.mapValues { message ->
                val trimmed = message.trim().removeSurrounding("\"")
                val decoded = Base64.getDecoder().decode(trimmed)
                String(decoded)
            }
        output.to("ttn-uplink-clean")
        return output
    }

    private fun decodePayload(bytes: ByteArray): MeasureDecoded {
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
        )
    }

    private fun decodeUnit(code: Int): String =
        when (code) {
            1 -> "°C"
            2 -> "%"
            3 -> "Pa"
            else -> "unknown"
        }
}
