package ar.edu.austral.inf.sd

import ar.edu.austral.inf.sd.server.api.PlayApiService
import ar.edu.austral.inf.sd.server.api.RegisterNodeApiService
import ar.edu.austral.inf.sd.server.api.RelayApiService
import ar.edu.austral.inf.sd.server.api.BadRequestException
import ar.edu.austral.inf.sd.server.api.ReconfigureApiService
import ar.edu.austral.inf.sd.server.api.UnregisterNodeApiService
import ar.edu.austral.inf.sd.server.model.PlayResponse
import ar.edu.austral.inf.sd.server.model.RegisterResponse
import ar.edu.austral.inf.sd.server.model.Signature
import ar.edu.austral.inf.sd.server.model.Signatures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.random.Random

@Component
class ApiServicesImpl : RegisterNodeApiService, RelayApiService, PlayApiService, UnregisterNodeApiService,
    ReconfigureApiService {

    @Value("\${server.name:nada}")
    private val myServerName: String = ""

    @Value("\${server.port:8080}")
    private val myServerPort: Int = 0
    private val nodes: MutableList<RegisterResponse> = mutableListOf()
    private var nextNode: RegisterResponse? = null
    private val messageDigest = MessageDigest.getInstance("SHA-512")
    private val salt = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    private val currentRequest
        get() = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).request
    private var resultReady = CountDownLatch(1)
    private var currentMessageWaiting = MutableStateFlow<PlayResponse?>(null)
    private var currentMessageResponse = MutableStateFlow<PlayResponse?>(null)
    private var xGameTimestamp: Int = 0
    private val timeout : Int = 10000

    override fun registerNode(host: String?, port: Int?, uuid: UUID?, salt: String?, name: String?): RegisterResponse {

        val nextNode = if (nodes.isEmpty()) {
            // es el primer nodo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, timeout, xGameTimestamp)
            nodes.add(me)
            me
        } else {
            nodes.last()
        }
        val node = RegisterResponse(host!!, port!!, timeout, xGameTimestamp)
        nodes.add(node)

        return RegisterResponse(nextNode.nextHost, nextNode.nextPort, nextNode.timeout, xGameTimestamp)
    }

    override fun relayMessage(message: String, signatures: Signatures, xGameTimestamp: Int?): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), salt)
        val receivedContentType = currentRequest.getPart("message")?.contentType ?: "nada"
        val receivedLength = message.length
        val newSignature = clientSign(message, receivedContentType)
        val updatedSignatures = signatures.copy( signatures.items + newSignature)

        if (nextNode != null) {
            // Soy un relé. busco el siguiente y lo mando
            try {
                sendRelayMessage(message, receivedContentType, nextNode!!, updatedSignatures)
            } catch (e: Exception) {
                // Error al enviar al siguiente nodo, envía al coordinador
                sendRelayMessage(message, receivedContentType, nodes.first(), updatedSignatures)
                throw BadRequestException("Could not relay message, fallback to coordinator") // Status 503
            }
        } else {
            // me llego algo, no lo tengo que pasar
            if (currentMessageWaiting.value == null) throw BadRequestException("no waiting message")
            val current = currentMessageWaiting.getAndUpdate { null }!!
            val response = current.copy(
                contentResult = if (receivedHash == current.originalHash) "Success" else "Failure",
                receivedHash = receivedHash,
                receivedLength = receivedLength,
                receivedContentType = receivedContentType,
                signatures = signatures
            )
            currentMessageResponse.update { response }
            resultReady.countDown()
        }
        return Signature(
            name = myServerName,
            hash = receivedHash,
            contentType = receivedContentType,
            contentLength = receivedLength
        )
    }

    override fun sendMessage(body: String): PlayResponse {
        if (nodes.isEmpty()) {
            // inicializamos el primer nodo como yo mismo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, "", "")
            nodes.add(me)
        }
        currentMessageWaiting.update { newResponse(body) }
        val contentType = currentRequest.contentType
        sendRelayMessage(body, contentType, nodes.last(), Signatures(listOf()))
        resultReady.await()
        resultReady = CountDownLatch(1)
        return currentMessageResponse.value!!
    }

    override fun unregisterNode(uuid: UUID?, salt: String?): String {
        if (uuid == null || salt == null) {
            throw BadRequestException("Invalid parameters")
        }

        val node = nodes.find { it.uuid == uuid }
        if (node == null || node.hash != salt) {
            throw BadRequestException("Invalid UUID or salt") // Status 400
        }

        nodes.remove(node)
        return "Node unregistered successfully"
    }

    override fun reconfigure(
        uuid: UUID?,
        salt: String?,
        nextHost: String?,
        nextPort: Int?,
        xGameTimestamp: Int?
    ): String {
        TODO("Not yet implemented")
    }

    internal fun registerToServer(registerHost: String, registerPort: Int) {
        try {
            val restTemplate = RestTemplate()
            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
            }

            val body = mapOf(
                "host" to myServerName,
                "port" to myServerPort,
                "uuid" to UUID.randomUUID().toString(),
                "salt" to salt,
                "name" to "Node-${myServerName}:${myServerPort}"
            )

            val entity = HttpEntity(body, headers)
            val response = restTemplate.postForEntity("http://$registerHost:$registerPort/register-node", entity, RegisterResponse::class.java)

            if (response.statusCode.is2xxSuccessful) {
                nextNode = response.body
                println("Registered successfully: nextNode = $nextNode")
            } else {
                throw Exception("Failed to register to server")
            }
        } catch (e: Exception) {
            println("Error during registration: ${e.message}")
        }
    }

    private fun sendRelayMessage(
        body: String,
        contentType: String,
        relayNode: RegisterResponse,
        signatures: Signatures
    ) {
        try {
            val restTemplate = RestTemplate()
            val headers = HttpHeaders().apply {
                set("X-Game-Timestamp", xGameTimestamp.toString())
            }

            val bodyPart = LinkedMultiValueMap<String, Any>().apply {
                add("message", body)
                add("signatures", signatures)
            }

            val entity = HttpEntity(bodyPart, headers)
            val response = restTemplate.postForEntity("http://${relayNode.nextHost}:${relayNode.nextPort}/relay", entity, String::class.java)

            if (!response.statusCode.is2xxSuccessful) {
                throw Exception("Failed to relay message")
            }
        } catch (e: Exception) {
            throw e // Fallar y dejar que se gestione en otro lado
        }
    }

    private fun clientSign(message: String, contentType: String): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), salt)
        return Signature(myServerName, receivedHash, contentType, message.length)
    }

    private fun newResponse(body: String) = PlayResponse(
        "Unknown",
        currentRequest.contentType,
        body.length,
        doHash(body.encodeToByteArray(), salt),
        "Unknown",
        -1,
        "N/A",
        Signatures(listOf())
    )

    private fun doHash(body: ByteArray, salt: String): String {
        val saltBytes = Base64.getDecoder().decode(salt)
        messageDigest.update(saltBytes)
        val digest = messageDigest.digest(body)
        return Base64.getEncoder().encodeToString(digest)
    }

    companion object {
        fun newSalt(): String = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    }
}