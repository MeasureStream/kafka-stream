package it.polito.measurestream.kafkastream.streams
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.measurestream.kafkastream.dto.MeasureDecoded
import it.polito.measurestream.kafkastream.dto.TTNMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Branched
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Produced
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64

@Component
class TTNStream(
    private val objectMapper: ObjectMapper,
    private val integerSerde: Serde<Int>,
    private val stringSerde: Serde<String>,
) {
    fun ttnUplinkProcessor(builder: StreamsBuilder): KStream<Int, String> {
        val input: KStream<ByteArray, String> = builder.stream("ttn-uplink", Consumed.with(Serdes.ByteArray(), Serdes.String()))

        val decodedStream = input.map { _, message ->
            val ttnMessage = decodeMessage(message)
            KeyValue(ttnMessage.fport, ttnMessage)
        }

        decodedStream.mapValues { ttnMessage ->
            val signalInfo = mapOf(
                "devEUI" to ttnMessage.devEUI,
                "rssi" to ttnMessage.LoRarssi,
                "dataRate" to ttnMessage.dataRate,
                "airtime" to ttnMessage.consumedAirtime,
                "time" to ttnMessage.time
            )
            objectMapper.writeValueAsString(signalInfo)
        }.to("ttn-uplink-signal-quality", Produced.with(integerSerde, Serdes.String()))

        val processed: KStream<Int, String> = decodedStream.mapValues { ttnMessage ->
            when (ttnMessage.fport) {
                1 -> decodePayload1(ttnMessage.payload, ttnMessage.devEUI, ttnMessage.time, ttnMessage.LoRarssi)
                10 -> decodePayload10(ttnMessage.payload, ttnMessage.devEUI)
                16 -> decodePayload16(ttnMessage.payload, ttnMessage.devEUI)
                else -> ttnMessage.payload
            }
        }.filter { _, value -> value != null && value.isNotBlank() }

        processed
            .split()
            .branch(
                { key, _ -> key == 1 },
                Branched.withConsumer { ks ->
                    ks.to("ttn-uplink-measure", Produced.with(integerSerde, Serdes.String()))
                },
            ).branch(
                { key, _ -> key == 2 },
                Branched.withConsumer { ks ->
                    ks.to("ttn-uplink-command", Produced.with(integerSerde, Serdes.String()))
                },
            )
            .branch({ key, _ -> key == 3 }, Branched.withConsumer { ks -> // TODO(DA RIMUOVERE ERA LA FPPORT UTILIZZATA IN PRECEDENZA)
                // Invio al topic mu-registration per il MeasureManager
                ks.to("mu-registration", Produced.with(integerSerde, Serdes.String()))
            })
            .branch({ key, _ -> key == 10 }, Branched.withConsumer { ks ->
                ks.to("cu-status", Produced.with(integerSerde, Serdes.String()))
            })
            .branch({ key, _ -> key == 16 }, Branched.withConsumer { ks ->
                ks.to("cu-join-notification", Produced.with(integerSerde, Serdes.String()))
            })
            .defaultBranch(
                Branched.withConsumer { ks ->
                    ks.to("ttn-uplink-error", Produced.with(integerSerde, Serdes.String()))
                },
            )
        return processed
    }

    private fun decodeMessage(message: String): TTNMessage {
        val trimmed = message.trim().removeSurrounding("\"")
        val decoded = Base64.getDecoder().decode(trimmed)
        try {
            val jsonStr = String(decoded)
            println("RAW MESSAGE: $jsonStr")
            val root: JsonNode = objectMapper.readTree(jsonStr)
            val frmPayload =
                root["uplink_message"]?.get("frm_payload")?.asText()
                    ?: throw Exception("Missing frm_payload in message")
            val fport = root["uplink_message"]?.get("f_port")?.asInt() ?: throw Exception("Missing f_port in the message")

            // val time = root["uplink_message"]?.get("settings")?.get("received_at")?.asText() ?: throw Exception("Missing time in the message")
            val time =
                root["uplink_message"]?.get("received_at")?.asText() ?: root["uplink_message"]?.get("settings")?.get("time")?.asText()
                    ?: throw Exception("Missing time in the message")

            val rssi: Int =
                root["uplink_message"]
                    ?.get("rx_metadata")
                    ?.get(0)
                    ?.get("rssi")
                    ?.asInt() ?: throw Exception("Missing rssi in the message")

            val devEui =
                root["end_device_ids"]?.get("dev_eui")?.asText()
                    ?: root["identifiers"]
                        ?.get(0)
                        ?.get("device_ids")
                        ?.get("dev_eui")
                        ?.asText()
            // root.get("dev_eui")?.asText()
            // println("This is the dev_eui: $devEui")

            val uplink = root["uplink_message"] ?: throw Exception("Not an uplink message")


            // Estrazione metadati radio (Settings)
            val settings = uplink["settings"]
            val dataRateModulation = settings?.get("data_rate")?.get("lora")

            val sf = dataRateModulation?.get("spreading_factor")?.asInt() ?: 0
            val bw = dataRateModulation?.get("bandwidth")?.asLong() ?: 0L
            val airtime = uplink["consumed_airtime"]?.asText() ?: "0s"
            //val payloadSize = getPayloadSize(frmPayload)
            val dataRate = calculateDataRate(sf, bw)


            // beware that frmPayload is encoded
            return TTNMessage(fport, frmPayload, if (devEui.isNullOrEmpty()) "NOT FOUND" else devEui, time, rssi, sf, bw, dataRate, airtime)
        } catch (e: Exception) {
            println("Error parsing message: $message")
            e.printStackTrace()
            throw e
        }
    }

    private fun decodePayload1(
        frmPayload: String,
        devEUI: String,
        time: String,
        LoRarssi: Int,
    ): String {
        val bytes = Base64.getDecoder().decode(frmPayload)

        // Nuovo check: MUID(4) + RSSI(1) + Temp(2) = 7 byte
        if (bytes.size < 7) {
            println("Payload too short: ${bytes.size} bytes")
            return ""
        }

        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)

        // 1) MUID (4 byte) - Lo leggiamo come Int (Unsigned 32-bit nel buffer)
        val muid = buffer.int.toLong() and 0xFFFFFFFFL

        // 2) RSSI (1 byte)
        val rssi = buffer.get().toInt()

        // 3) Temperatura (2 byte) - Little Endian come prima
        // Nota: buffer.int ha spostato la posizione a 4, buffer.get() a 5.
        // La temperatura è ai byte 5 e 6 (0-indexed)
        val lsb = bytes[5].toInt() and 0xFF
        val msb = bytes[6].toInt() and 0xFF

        // Ricostruzione Signed Int16 (Little Endian)
        val raw = (msb shl 8) or lsb
        val tempInt = if (raw and 0x8000 != 0) raw or -0x10000 else raw
        val temperature = tempInt.toDouble() / 100.0

        // Mappatura del nodeId basata sul MUID invece che sul MAC
        //val nodeId = if (muid == 1677721601L) 1L else 2L

        println("MUID ricevuto: $muid | Temp: $temperature | RSSI: $rssi")

        val m = MeasureDecoded(
            value = temperature,
            unit = "°C", // O decodeUnit(1) se preferisci
            nodeId = muid,
            time = time,
            rssi = rssi,
            devEUI = devEUI,
            LoRarssi = LoRarssi,
        )

        return Json.encodeToString(m)
    }

    private fun decodePayload3(frmPayload: String): String {
        val bytes = Base64.getDecoder().decode(frmPayload)

        // Usiamo LITTLE_ENDIAN perché il tuo sensore trasmette i byte meno significativi prima
        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)

        // 1) CUID (4 byte - Little Endian)
        // Usiamo toLong() e la maschera per evitare i numeri negativi (unsigned)
        val cuid = if (bytes.size >= 4) (buffer.int.toLong() and 0xFFFFFFFFL) else 0L

        // 2) MUID (4 byte - Little Endian)
        val muidRaw = if (bytes.size >= 8) (buffer.int.toLong() and 0xFFFFFFFFL) else 0L

        // 3) MODELMU
        // Se il modello è il byte più significativo (MSB) del MUID:
        // In Little Endian, dopo aver letto l'intero con buffer.int,
        // il MSB è quello che era all'ultimo posto nel buffer (offset 7).
        val model = (muidRaw shr 24) and 0xFFL

        val registrationMap = mapOf(
            "CUID" to cuid.toString(),
            "MUID" to muidRaw.toString(),
            "MODELMU" to model.toString()
        )

        println("REGISTRATION: CU=$cuid, MU=$muidRaw, MODEL=$model")

        return objectMapper.writeValueAsString(registrationMap)
    }

    private fun decodePayload10(frmPayload: String, devEUI: String): String {
        val bytes = Base64.getDecoder().decode(frmPayload)

        // Verifica lunghezza minima: Model(2) + Bat(1) + Status(2) = 6 byte
        if (bytes.size < 4) {
            println("Payload fport 10 troppo corto: ${bytes.size} bytes")
            return ""
        }

        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)

        // Byte 0: Opcode (lo leggiamo ma non lo mettiamo nel DTO se non serve)


        // Byte 1-2: CU Model (Short - 2 byte)
        val model = buffer.short.toInt() and 0xFFFF

        // Byte 3: Battery (1 byte)
        val rawbattery = buffer.get().toInt() and 0xFF

        val isCharging = rawbattery == 255
        
        val battery = if ( isCharging) 100 else ((rawbattery.toDouble() / 254.0) * 100.0).toInt()

        // Byte 4: Status ( 1 byte)
        val statusRaw = buffer.get().toInt() and 0xFFFF

        // Creiamo il DTO per il MeasureManager
        // Nota: devEUI viene convertito da stringa HEX (TTN) a Long
        val update = mapOf(
            "devEui" to devEUI.toLong(16), // TTN manda HEX, noi vogliamo il Long
            "model" to model,
            "batteryLevel" to battery,
            "isCharging" to isCharging,
            "statusRaw" to statusRaw
        )

        println("CU STATUS [FPort 10]: DevEUI=$devEUI, Model=$model, Bat=$battery%, isCharging=$isCharging, Status=$statusRaw")

        return objectMapper.writeValueAsString(update)
    }

    private fun decodePayload16(frmPayload: String, devEUI: String): String {
        val bytes = Base64.getDecoder().decode(frmPayload)

        // Almeno Opcode(1) + 1 MU(5) = 6 byte
        if (bytes.size < 5) return ""

        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)



        val muList = mutableListOf<Map<String, Any>>()

        // Ogni MU sono 5 byte. Continuiamo finché ci sono almeno 5 byte rimasti
        while (buffer.remaining() >= 5) {
            val extendedId = buffer.int
            val localId = buffer.get().toInt() and 0xFF

            // Estrarre il modello dall'ExtendedID (come fatto in precedenza)
            // Se il modello sono i primi 8 bit dell'ExtendedID:
            val model = (extendedId shr 16) and 0xFFFF

            muList.add(mapOf(
                "extendedId" to extendedId,
                "localId" to localId,
                "model" to model
            ))
        }

        val joinNotification = mapOf(
            "devEui" to devEUI.toLong(16),
            "muList" to muList
        )

        println("JOIN NOTIFICATION: CU=$devEUI, MU trovate=${muList.size}")
        return objectMapper.writeValueAsString(joinNotification)
    }

    private fun calculateDataRate(sf: Int, bw: Long): String {
        return when {
            sf == 12 && bw == 125000L  -> "DR0"
            sf == 11 && bw == 125000L  -> "DR1"
            sf == 10 && bw == 125000L  -> "DR2"
            sf == 9  && bw == 125000L  -> "DR3"
            sf == 8  && bw == 125000L -> "DR4"
            sf == 7  && bw == 125000L -> "DR5"
            sf == 7  && bw == 250000L -> "DR6"
            else -> "DR_INVALID_OR_OVERSIZE"
        }
    }

    private fun getPayloadSize(base64String: String): Int {
        if (base64String.isEmpty()) return 0

        val l = base64String.length
        val padding = when {
            base64String.endsWith("==") -> 2
            base64String.endsWith("=") -> 1
            else -> 0
        }

        return (l * 3 / 4) - padding
    }
}
