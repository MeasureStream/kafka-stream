package it.polito.measurestream.kafkastream.dto

data class TTNMessage(
    val fport: Int,
    val payload: String,
)
