package it.polito.measurestream.kafka_stream

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.kafka.annotation.EnableKafkaStreams

@SpringBootApplication
@EnableKafkaStreams
class KafkaStreamApplication

fun main(args: Array<String>) {
	runApplication<KafkaStreamApplication>(*args)
}
