package ar.edu.austral.inf.sd

import ar.edu.austral.inf.sd.server.singleton.SaltHolder
import ar.edu.austral.inf.sd.server.singleton.UUIDHolder
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

@Component
class QuitProtocol(
    private val uuidHolder: UUIDHolder,
    private val saltHolder: SaltHolder
) : DisposableBean {
    @Value("\${register.host:}")
    var registerHost: String? = ""

    @Value("\${register.port:}")
    var registerPort: Int? = -1


    override fun destroy() {
        if (registerHost  == ""|| registerPort == -1) {
            return
        }

        try{
            val restTemplate = RestTemplate()
            val headers = HttpHeaders().apply {
                contentType = org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED
            }

            val url = "http://${registerHost}:${registerPort}/unregister-node?uuid=${uuidHolder.generatedUUID}&salt=${saltHolder.salt}"
            val response = restTemplate.postForEntity(url, HttpEntity(null, headers), String::class.java)

            if (response.statusCode == HttpStatus.OK) {
                println("Successfully unregistered node with UUID: ${uuidHolder.generatedUUID}")
            } else {
                println("Failed to unregister node. Status code: ${response.statusCode}")
            }
        } catch (e: Exception) {
            println("Error during unregistering node: \${e.message}")
        }
    }
}