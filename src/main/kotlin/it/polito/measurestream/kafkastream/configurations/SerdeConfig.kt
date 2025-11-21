package it.polito.measurestream.kafkastream.configurations
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SerdeConfig {
    @Bean
    fun integerSerde(): Serde<Int> = Serdes.Integer()

    @Bean
    fun stringSerde(): Serde<String> = Serdes.String()
}
