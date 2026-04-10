package it.polito.measurestream.kafkastream.streams

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.measurestream.kafkastream.configurations.CUConfigCommandDTO
import it.polito.measurestream.kafkastream.configurations.TTNDownlink
import it.polito.measurestream.kafkastream.configurations.DownlinkPayload
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Produced
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafkaStreams
import org.springframework.kafka.support.serializer.JsonSerde
import java.util.Base64

@Configuration
@EnableKafkaStreams
class DownlinkStreamProcessor(private val objectMapper: ObjectMapper) {

    @Bean
    fun downlinkKStream(builder: StreamsBuilder): KStream<String, String> {

        val cuConfigSerde = JsonSerde(CUConfigCommandDTO::class.java, objectMapper)
        cuConfigSerde.deserializer().addTrustedPackages("*")

        val inputStr: KStream<String, CUConfigCommandDTO> = builder.stream(
            "cu-configuration",
            Consumed.with(Serdes.String(), cuConfigSerde)
        )

        val outputStream: KStream<String, String> = inputStr
            .mapValues { command ->
                try {
                    // 1. Prepariamo il payload binario in Base64
                    val payloadBytes = byteArrayOf(
                        0x00.toByte(),
                        (command.pollingInterval and 0xFF).toByte()
                    )
                    val base64Payload = Base64.getEncoder().encodeToString(payloadBytes)

                    // 2. Costruiamo l'oggetto interno (il singolo downlink)
                    val downlinkElement = mapOf(
                        "frm_payload" to base64Payload,
                        "f_port" to 0x0A,
                        "priority" to "NORMAL",
                        "confirmed" to false
                    )

                    // 3. Costruiamo il wrapper finale richiesto da TTN: {"downlinks": [...]}
                    val ttnFinalWrapper = mapOf(
                        "downlinks" to listOf(downlinkElement)
                    )

                    // 4. Serializziamo in stringa JSON
                    val jsonOutput = objectMapper.writeValueAsString(ttnFinalWrapper)

                    println("DEBUG - Produced JSON: $jsonOutput")
                    jsonOutput
                } catch (e: Exception) {
                    println("Errore trasformazione: ${e.message}")
                    null
                }
            }
            .filter { _, value -> value != null }
            .mapValues { v -> v!! }

        // Usiamo il deviceId del comando originale come CHIAVE del messaggio Kafka
        // Utile per mantenere l'ordine dei messaggi per lo stesso dispositivo
        outputStream.to("ttn-downlink", Produced.with(Serdes.String(), Serdes.String()))

        return outputStream
    }
}