package it.polito.measurestream.kafkastream.dto
import java.time.Instant

data class MeasureDecoded(
    val value: Double,
    val unit: String,
    val nodeId: Long,
    val time: Instant,
)
