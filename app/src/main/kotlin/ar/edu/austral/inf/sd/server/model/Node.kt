package ar.edu.austral.inf.sd.server.model

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Min

data class Node(
    @get:JsonProperty("nextHost", required = true)
    var nextHost: kotlin.String,
    @get:JsonProperty("nextPort", required = true)
    var nextPort: kotlin.Int,
    @get:Min(0)
    @get:JsonProperty("timeout", required = true)
    val timeout: kotlin.Int,
    @get:Min(0)
    @get:JsonProperty("xGameTimestamp", required = true)
    val xGameTimestamp: kotlin.Int,
    @get:JsonProperty("salt", required = true)
    val salt: kotlin.String,
    @get:JsonProperty("uuid", required = true)
    val uuid: java.util.UUID
)