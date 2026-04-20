package it.polito.measurestream.kafkastream.configurations

import it.polito.measurestream.kafkastream.streams.CalibrationStream
import it.polito.measurestream.kafkastream.streams.TTNStream
import org.apache.kafka.streams.StreamsBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafkaStreams

@Configuration
@EnableKafkaStreams
class KafkaStreamsConfig(
    private val ttnStream: TTNStream,
    private val calibrationStream: CalibrationStream
) {
    @Bean
    fun kStream(builder: StreamsBuilder) {
        // Avvia il processore TTN (esistente)
        ttnStream.ttnUplinkProcessor(builder)

        // Avvia il processore Calibrazioni (nuovo)
        calibrationStream.calibrationProcessor(builder)

        println("[KAFKA-STREAMS] Entrambe le topologie (TTN e Calibrazioni) sono state registrate.")
    }
}
