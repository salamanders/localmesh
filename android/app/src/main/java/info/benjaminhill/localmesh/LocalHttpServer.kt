package info.benjaminhill.localmesh

import info.benjaminhill.localmesh.mesh.P2PBridgeService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.net.BindException
import java.text.DateFormat
import java.util.Date

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
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request format")
                    )
                }
            }
            get("/messages") {
                call.respond(service.getReceivedMessages())
            }
            get("/test") {
                val statusResponse = StatusResponse(
                    status = "RUNNING",
                    id = service.getEndpointName(),
                    peerCount = service.getConnectedPeerCount(),
                    peerIds = service.getConnectedPeerIds()
                )
                val statusHtml = """
                    <strong>ID:</strong> ${statusResponse.id}<br>
                    <strong>Peers:</strong> ${statusResponse.peerCount} (${
                    statusResponse.peerIds.joinToString(
                        ", "
                    )
                })
                """.trimIndent()

                val messages = service.getReceivedMessages()
                val messagesHtml = if (messages.isEmpty()) {
                    "<p>No messages received yet.</p>"
                } else {
                    val timeFormatter = DateFormat.getTimeInstance(DateFormat.MEDIUM)
                    messages.joinToString("\n") { msg ->
                        """
                        <div class="message">
                            <strong>From:</strong> ${msg.from} <small>(${
                            timeFormatter.format(
                                Date(msg.timestamp)
                            )
                        })</small><br>
                            ${msg.payload}
                        </div>
                        """.trimIndent()
                    }
                }
                call.respondText(getTestPageHtml(statusHtml, messagesHtml), ContentType.Text.Html)
            }
            post("/send-message-from-test") {
                val parameters = call.receiveParameters()
                val message = parameters["messageInput"]
                if (message != null) {
                    service.sendMessage(message)
                }
                call.respondRedirect("/test")
            }
        }
    }

    fun start(): Boolean {
        try {
            logMessageCallback("Attempting to start LocalHttpServer...")
            server.start(wait = false)
            logMessageCallback("LocalHttpServer started successfully.")
            return true
        } catch (_: BindException) {
            logMessageCallback("Port $PORT is already in use. Please close the other application and restart the service.")
            return false
        } catch (e: Exception) {
            logMessageCallback("An unexpected error occurred while starting the LocalHttpServer: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    fun stop() {
        server.stop(1000, 1000)
    }

    private fun getTestPageHtml(statusHtml: String, messagesHtml: String): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>LocalMesh Test Page</title>
                <style>
                    body { font-family: sans-serif; margin: 2em; }
                    #messages { border: 1px solid #ccc; padding: 1em; margin-top: 1em; min-height: 100px; max-height: 300px; overflow-y: auto; }
                    .message { border-bottom: 1px solid #eee; padding: 0.5em; }
                    form { margin-top: 1em; }
                    input[type="text"] { width: 70%; padding: 0.5em; }
                    button { padding: 0.5em 1em; }
                </style>
            </head>
            <body>
                <h1>LocalMesh Test Page</h1>
                
                <h2>Status</h2>
                <div id="status">${statusHtml}</div>
                
                <h2>Send Message</h2>
                <form action="/send-message-from-test" method="post">
                    <input type="text" name="messageInput" size="40" placeholder="Enter message to send">
                    <button type="submit">Send</button>
                </form>
                
                <h2>Received Messages</h2>
                <div id="messages">${messagesHtml}</div>

                <hr>
                <small>Refresh the page to see updates.</small>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        const val PORT = 8099
    }
}