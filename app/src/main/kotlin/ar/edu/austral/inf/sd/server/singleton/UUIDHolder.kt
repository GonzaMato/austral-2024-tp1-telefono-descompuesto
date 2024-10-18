package ar.edu.austral.inf.sd.server.singleton

import org.springframework.stereotype.Component
import java.util.UUID

@Component
class UUIDHolder {
    val generatedUUID : UUID = UUID.randomUUID()
}