package it.polito.measurestream.kafkastream.streams

import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Produced
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

@Component
class CalibrationStream {

    fun calibrationProcessor(builder: StreamsBuilder) {
        // 1. Leggiamo il topic MQTT come ByteArray perché il contenuto è GZIP (Binario)
        val input: KStream<String, ByteArray> = builder.stream(
            "mqtt-calibration-raw",
            Consumed.with(Serdes.String(), Serdes.ByteArray())
        )

        val decompressedStream = input.mapValues { _, gzippedBytes ->
            try {
                // 2. Decompressione GZIP
                val jsonString = GZIPInputStream(ByteArrayInputStream(gzippedBytes))
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }

                println("[CALIBRATION] Ricevuto e decompresso step di calibrazione correttamente")
                jsonString // Restituiamo il JSON "in chiaro"
            } catch (e: Exception) {
                println("[ERROR] Fallimento decompressione calibrazione: ${e.message}")
                null
            }
        }.filter { _, value -> value != null }

        // 3. Scriviamo sul nuovo topic "calibrations"
        decompressedStream.to(
            "calibrations",
            Produced.with(Serdes.String(), Serdes.String())
        )
    }
}