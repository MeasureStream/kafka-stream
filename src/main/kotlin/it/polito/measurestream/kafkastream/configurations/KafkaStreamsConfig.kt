import it.polito.measurestream.kafkastream.streams.TTNStream
import org.apache.kafka.streams.StreamsBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafkaStreams

@Configuration
@EnableKafkaStreams
class KafkaStreamsConfig(
    private val ttnStream: TTNStream,
) {
    @Bean
    fun kStream(builder: StreamsBuilder) = ttnStream.ttnUplinkProcessor(builder)
}
