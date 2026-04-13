package it.polito.measurestream.kafkastream.streams

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.measurestream.kafkastream.configurations.DownlinkRequestDTO
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.support.serializer.JsonSerde
import java.util.*

@Configuration
class DownlinkMqttProcessor(
    private val objectMapper: ObjectMapper,
    @Value("\${mqtt.broker.url:ssl://eu1.cloud.thethings.network:8883}") private val brokerUrl: String,
    @Value("\${mqtt.username}") private val mqttUsername: String,
    @Value("\${mqtt.password}") private val mqttPassword: String
) {

    private val mqttClient: MqttClient by lazy {
        val clientId = "ks-publisher-${UUID.randomUUID()}"
        val client = MqttClient(brokerUrl, clientId, MemoryPersistence())
        val options = MqttConnectOptions().apply {
            userName = mqttUsername
            password = mqttPassword.toCharArray()
            isCleanSession = true
        }
        client.connect(options)
        client
    }

    @Bean
    fun mqttSinkStream(builder: StreamsBuilder): KStream<String, DownlinkRequestDTO> {
        val downlinkSerde = JsonSerde(DownlinkRequestDTO::class.java, objectMapper)
        downlinkSerde.deserializer().addTrustedPackages("*")

        val stream = builder.stream("ttn-downlink-clean", Consumed.with(Serdes.String(), downlinkSerde))

        stream.foreach { _, req ->
            try {
                // Conversione dei byte grezzi in Base64 (richiesta da TTN)
                val base64Payload = Base64.getEncoder().encodeToString(req.rawPayload)

                // Struttura JSON Standard TTN
                val ttnPayload = mapOf(
                    "downlinks" to listOf(
                        mapOf(
                            "frm_payload" to base64Payload,
                            "f_port" to req.fport,
                            "priority" to req.priority,
                            "confirmed" to req.confirmed
                        )
                    )
                )

                val json = objectMapper.writeValueAsString(ttnPayload)

                // Topic dinamico: v3/app-id@ttn/devices/device-id/down/push
                val topic = "v3/$mqttUsername/devices/${req.deviceId}/down/push"

                val message = MqttMessage(json.toByteArray()).apply { qos = 1 }
                mqttClient.publish(topic, message)

                println("MQTT SUCCESS: Sent to ${req.deviceId}. Payload (Hex): ${req.rawPayload.joinToString("") { "%02x".format(it) }}")
            } catch (e: Exception) {
                println("MQTT ERROR: ${e.message}")
                e.printStackTrace()
            }
        }
        return stream
    }
}