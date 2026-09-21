package it.polito.measurestream.kafkastream.configurations

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty

data class CUConfigCommandDTO(
    val deviceId: String = "",
    val devEui: Long = 0L,
    val pollingInterval: Int = 0
)

data class TTNDownlink(
    @JsonProperty("device_id") val deviceId: String,
    val downlinks: List<DownlinkPayload>
)

data class DownlinkPayload(
    @JsonProperty("f_port") val fPort: Int = 15,
    @JsonProperty("frm_payload") val frmPayload: String, // Base64
    val priority: String = "NORMAL",
    val confirmed: Boolean = true
)


data class DownlinkRequestDTO(
    val deviceId: String,
    val rawPayload: ByteArray, // Byte grezzi, non ancora Base64
    // sensor-manager invia "fport" in modo esplicito; l'alias accetta anche "fPort",
    // cioè i messaggi prodotti prima di quella modifica o durante un deploy non simultaneo.
    @JsonProperty("fport") @JsonAlias("fPort") val fport: Int = 15,
    val priority: String = "NORMAL",
    val confirmed: Boolean = false
)