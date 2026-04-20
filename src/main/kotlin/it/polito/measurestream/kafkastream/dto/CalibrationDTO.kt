package it.polito.measurestream.kafkastream.dto

import kotlinx.serialization.Serializable

@Serializable
data class StepDataDTO(
    val target: Float,
    val step_index: Int,
    val ref_readings: List<Float>,
    val sensor_b64: String // Questo contiene i byte compressi/codificati del sensore
)

@Serializable
data class FinalCalibrationDTO(
    val calibration_id: String,
    val sensor_id: Int,
    val sensor_freq_hz: Int,
    val steps: List<StepDataDTO>
)