package it.polito.measurestream.kafkastream.dto

data class TTNMessage(
    val fport: Int,
    val payload: String,
    val devEUI: String,
    val time: String,
    val LoRarssi: Int,
    val spreadingFactor: Int,
    val bandwidth: Long,
    val dataRate: String,
    val consumedAirtime: String
)
