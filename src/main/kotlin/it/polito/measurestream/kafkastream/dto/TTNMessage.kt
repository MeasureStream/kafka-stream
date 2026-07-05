package it.polito.measurestream.kafkastream.dto

data class TTNMessage(
    val fport: Int,
    val payload: String,
    val deviceId: String,
    val devEUI: String,
    val time: String,
    val LoRarssi: Int,
    val spreadingFactor: Int,
    val bandwidth: Long,
    val dataRate: String,
    val consumedAirtime: String,
    /**
     * Frame counter LoRaWAN dell'uplink (uplink_message.f_cnt nel JSON TTN).
     * TTN omette il campo quando vale 0, quindi il default è 0.
     */
    val fCnt: Int = 0
)
