package it.polito.measurestream.kafka_stream.dto
import java.time.Instant

data class MeasureDecoded(
    val value: Double,
    val unit: String,
    val nodeId: Long,
    val time: Instant,
)
