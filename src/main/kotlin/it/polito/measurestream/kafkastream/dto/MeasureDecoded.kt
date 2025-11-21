package it.polito.measurestream.kafkastream.dto
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class MeasureDecoded(
    val value: Double,
    val unit: String,
    val nodeId: Long,
    val time: String,
)
