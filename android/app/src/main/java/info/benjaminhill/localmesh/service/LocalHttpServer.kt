package info.benjaminhill.localmesh.service

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.net.BindException

class LocalHttpServer(
    private val service: P2PBridgeService,
    private val logMessageCallback: (String) -> Unit
) {

    @Serializable
    data class SendMessageRequest(val message: String)

    @Serializable
    data class StatusResponse(
        val status: String,
        val id: String,
        val peerCount: Int,
        val peerIds: List<String>
    )


    private val server = embeddedServer(CIO, port = PORT) {
        install(ContentNegotiation) {
            json()
        }
        routing {
            get("/status") {
                call.respond(
                    StatusResponse(
                        status = "RUNNING",
                        id = service.getEndpointName(),
                        peerCount = service.getConnectedPeerCount(),
                        peerIds = service.getConnectedPeerIds()
                    )
                )
            }
            post("/send") {
                try {
                    val request = call.receive<SendMessageRequest>()
                    service.sendMessage(request.message)
                    call.respond(mapOf("status" to "accepted"))
                } catch (e: Exception) {
                    logMessageCallback("Failed to parse send request: ${e.message}")
                    call.respond(
                        io.ktor.http.HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request format")
                    )
                }
            }
            get("/messages") {
                call.respond(service.getReceivedMessages())
            }
        }
    }

    fun start() {
        try {
            logMessageCallback("Attempting to start LocalHttpServer...")
            server.start(wait = false)
            logMessageCallback("LocalHttpServer started successfully.")
        } catch (_: BindException) {
            logMessageCallback("Port $PORT is already in use. Please close the other application and restart the service.")
        } catch (e: Exception) {
            logMessageCallback("An unexpected error occurred while starting the LocalHttpServer: ${e.message}")
            e.printStackTrace()
        }
    }

    fun stop() {
        server.stop(1000, 1000)
    }

    companion object {
        const val PORT = 8099
    }
}
