package it.polito.measurestream.kafkastream.streams

import jakarta.annotation.PostConstruct
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.util.*
import java.util.zip.GZIPInputStream


@Component
class MqttCalibrationListener(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${mosquitto.broker}") private val brokerUrl: String,
    @Value("\${mosquitto.username}") private val mqttUsername: String,
    @Value("\${mosquitto.password}") private val mqttPassword: String
) {

    private lateinit var mqttClient: MqttClient

    @PostConstruct
    fun setup() {
        val clientId = "calibration-decompressor-${UUID.randomUUID()}"
        mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())

        val options = MqttConnectOptions().apply {
            userName = mqttUsername
            password = mqttPassword.toCharArray()
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 30
        }

        mqttClient.connect(options)

        // Sottoscrizione al topic dove il Raspberry invia i dati compressi
        // Usiamo il wildcard per catturare tutti i raspi e tutti gli step
        val topicFilter = "measurestream/lab/calibrator/+/data/raw/#"

        mqttClient.subscribe(topicFilter) { topic, message ->
            try {
                // 1. Decompressione GZIP
                val decompressedJson = decompress(message.payload)

                // 2. Invio al topic Kafka "calibrations"
                // Usiamo il topic MQTT come chiave Kafka per mantenere l'ordinamento per sensore
                kafkaTemplate.send("calibrations", topic, decompressedJson)

                println("[GZIP-BRIDGE] Decompresso step da topic: $topic (${message.payload.size}B -> ${decompressedJson.length}B)")
            } catch (e: Exception) {
                println("[ERROR] Errore processamento calibrazione su $topic: ${e.message}")
            }
        }

        println("[SYSTEM] MqttCalibrationListener attivo su $topicFilter")
    }

    private fun decompress(compressed: ByteArray): String {
        return GZIPInputStream(ByteArrayInputStream(compressed))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
}