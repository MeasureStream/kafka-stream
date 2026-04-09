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
        val inputStr: KStream<String, CUConfigCommandDTO> = builder.stream(
            "cu-configuration",
            Consumed.with(Serdes.String(), JsonSerde(CUConfigCommandDTO::class.java, objectMapper))
        )

        val outputStream: KStream<String, String> = inputStr
            .mapValues { command ->
                try {
                    // Protocollo: | 0x0A | 0x00 | Polling (1B) |
                    val payloadBytes = byteArrayOf(
                        0x0A.toByte(),
                        0x00.toByte(),
                        (command.pollingInterval and 0xFF).toByte()
                    )

                    val base64Payload = Base64.getEncoder().encodeToString(payloadBytes)

                    val ttnMessage = TTNDownlink(
                        deviceId = command.deviceId,
                        downlinks = listOf(DownlinkPayload(frmPayload = base64Payload))
                    )

                    objectMapper.writeValueAsString(ttnMessage)
                } catch (e: Exception) {
                    println("Errore trasformazione: ${e.message}")
                    null
                }
            }
            .filter { _, value -> value != null }
            .mapValues { v -> v!! } // Forza a non-null per il tipo di ritorno

        outputStream.to("ttn-downlink", Produced.with(Serdes.String(), Serdes.String()))

        return outputStream
    }
}