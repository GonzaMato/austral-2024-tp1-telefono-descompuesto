package ar.edu.austral.inf.sd.server.singleton

import org.springframework.stereotype.Component
import java.util.*
import kotlin.random.Random

@Component
class SaltHolder {
    val salt = Base64.getEncoder().encodeToString(Random.nextBytes(9))
}