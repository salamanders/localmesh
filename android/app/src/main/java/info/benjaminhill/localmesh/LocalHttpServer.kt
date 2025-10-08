package info.benjaminhill.localmesh

import android.content.Intent
import android.util.Log
import info.benjaminhill.localmesh.mesh.BridgeService
import info.benjaminhill.localmesh.mesh.HttpRequestWrapper

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.TextContent
import io.ktor.http.content.forEachPart
import io.ktor.http.formUrlEncode
import io.ktor.http.fromFileExtension
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.io.File
import java.io.IOException
import java.net.BindException
import java.net.URLDecoder
import io.ktor.server.cio.CIO as KtorCIO

/**
 * A Ktor-based web server that runs on the Android device, serving the web UI and handling API requests.
 * It is the single source of truth for the application's API.
 *
 * ## What it does
 * - Serves the static web assets (HTML, CSS, JS) for the frontend.
 * - Defines all API endpoints (e.g., `/status`, `/chat`, `/send-file`).
 * - Intercepts outgoing local requests and broadcasts them to peers as `HttpRequestWrapper` objects.
 * - Receives `HttpRequestWrapper` objects from peers (via `BridgeService`) and dispatches them as
 *   synthetic local requests to its own endpoints.
 *
 * ## What it doesn't do
 * - It does not directly handle P2P communication; that is done by `NearbyConnectionsManager`.
 * - It does not manage the service lifecycle; that is done by `BridgeService`.
 *
 * ## Comparison to other classes
 * - **[BridgeService]:** `LocalHttpServer` is the application's brain (API logic), while `BridgeService`
 *   is the heart, pumping data between the network and the brain.
 */


class LocalHttpServer(
    private val service: BridgeService,
    private val logMessageCallback: (String) -> Unit
) {

    // Define the possible broadcast strategies
    private sealed class BroadcastStrategy {
        data object LocalOnly : BroadcastStrategy() // Execute locally, do not broadcast.
        data object BroadcastAndExecute :
            BroadcastStrategy() // Broadcast to peers AND execute locally.

        data object BroadcastOnly :
            BroadcastStrategy() // Broadcast to peers, DO NOT execute locally.
        //data object ReplyOnly : BroadcastStrategy() // Reply to sender with the result of the execution.
    }

    private val httpClient = HttpClient(CIO)

    /**
     * A Ktor plugin that intercepts outgoing local requests and broadcasts them to peers.
     * It decides whether a request should be executed locally, broadcast to peers, or both.
     */
    internal val p2pBroadcastInterceptor =
        createApplicationPlugin(name = "P2PBroadcastInterceptor") {
            onCall { call ->
                // --- Step 1: Determine the Strategy ---
                val isFromPeer = call.request.queryParameters["sourceNodeId"] != null
                if (isFromPeer) {
                    // All requests from peers should execute locally and stop.
                    return@onCall
                }

                val path = call.request.path()

                val strategy = when (path) {
                    // These paths are broadcast to peers and also executed locally.
                    "/display" -> BroadcastStrategy.BroadcastAndExecute
                    // These paths are broadcast to peers and NOT executed locally on the originating device.
                    "/chat" -> BroadcastStrategy.BroadcastOnly

                    // All other paths (/status, /send-file, /assets) are local only.
                    else -> BroadcastStrategy.LocalOnly
                }

                // --- Step 2: Execute the Strategy ---
                if (strategy is BroadcastStrategy.LocalOnly) {
                    return@onCall // Let the request proceed to the route handler.
                }

                // --- Logic for Broadcasting ---
                // This code only runs for BroadcastOnly.

                // For POST requests, we need to read the body to broadcast it.
                // We DON'T need to cache it because we won't execute locally.
                val body: String = if (call.request.httpMethod == HttpMethod.Post) {
                    call.receiveText()
                } else {
                    ""
                }

                // Create and broadcast the wrapper.
                val wrapper = HttpRequestWrapper(
                    method = call.request.httpMethod.value,
                    path = path,
                    queryParams = call.request.queryParameters.formUrlEncode(),
                    body = body,
                    sourceNodeId = service.getEndpointName()
                )
                service.broadcast(wrapper.toJson())

                // Stop the pipeline for this request by responding immediately.
                call.respond(HttpStatusCode.OK, "Request broadcasted to peers.")
            }
        }

    private val server = embeddedServer(KtorCIO, port = PORT) {
        install(ContentNegotiation) {
            json()
        }
        install(p2pBroadcastInterceptor)

        routing {
            get("/folders") {
                try {
                    val assetList =
                        service.applicationContext.assets.list("web")?.toList() ?: emptyList()
                    call.respond(assetList)
                } catch (e: IOException) {
                    call.respond(HttpStatusCode.InternalServerError, "Error listing asset folders.")
                }
            }
            get("/status") {
                // A simple report of the service's status and number of peers
                call.respond(
                    mapOf(
                        "status" to HttpStatusCode.OK,
                        "id" to service.getEndpointName(),
                        "peerCount" to service.getConnectedPeerCount(),
                        "peerIds" to service.getConnectedPeerIds()
                    )
                )
            }

            post("/chat") {
                val body = call.attributes[RequestBodyAttribute]
                val message =
                    URLDecoder.decode(body, Charsets.UTF_8.name()).substringAfter("message=")
                val source = call.request.queryParameters["sourceNodeId"] ?: "local"
                logMessageCallback("Chat from $source: $message")
                call.respond(mapOf("status" to HttpStatusCode.OK))
            }

            get("/display") {
                val path = call.parameters["path"]
                if (path == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing path parameter")
                    return@get
                }
                val intent = Intent(service.applicationContext, WebViewActivity::class.java).apply {
                    // Correctly form the URL
                    putExtra(WebViewActivity.EXTRA_URL, "http://localhost:$PORT/$path")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
                call.respond(
                    mapOf(
                        "status" to HttpStatusCode.OK,
                        "url" to "http://localhost:$PORT/$path"
                    )
                )
            }

            // This endpoint is for receiving file *data* from the web UI
            post("/send-file") {
                val sourceNodeId = call.request.queryParameters["sourceNodeId"]
                if (sourceNodeId != null) {
                    // This call came from a peer, so the file is already being handled by the STREAM payload.
                    // We just log it.
                    val filename = call.request.queryParameters["filename"] ?: "unknown"
                    logMessageCallback("Received file request for '$filename' from peer '$sourceNodeId'. Awaiting stream.")
                    call.respond(HttpStatusCode.OK, "File request acknowledged from peer.")
                    return@post
                }

                // This is a new file upload from the local web UI
                val multipart = call.receiveMultipart()
                var responseSent = false
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        val originalFileName = part.originalFileName ?: "unknown.bin"
                        // The `readByteArray()` function from `kotlinx.io` is required here.
                        val fileBytes = part.provider().readRemaining().readByteArray()
                        val tempFile = File(
                            service.cacheDir,
                            "upload_temp_${System.currentTimeMillis()}_$originalFileName"
                        ).apply {
                            writeBytes(fileBytes)
                        }
                        // Use the BridgeService to send the file via a STREAM payload
                        service.sendFile(tempFile)
                        responseSent = true
                        call.respond(
                            mapOf(
                                "status" to HttpStatusCode.OK,
                                "file_status" to "file sending initiated",
                                "filename" to originalFileName
                            )
                        )
                    }
                    part.dispose()
                }
                if (!responseSent) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        "No file part found in multipart request."
                    )
                }
            }

            // This is a notification endpoint, not for data transfer
            post("/file-received") {
                val params = call.attributes[RequestBodyAttribute]
                val decodedParams = URLDecoder.decode(params, Charsets.UTF_8.name())
                val filename = decodedParams.substringAfter("filename=").substringBefore("&")
                val source = call.request.queryParameters["sourceNodeId"] ?: "local"
                logMessageCallback("Notification: File '$filename' was successfully received from $source.")
                call.respond(mapOf("status" to HttpStatusCode.OK))
            }

            get("/{path...}") {
                var path = call.parameters.getAll("path")?.joinToString("/") ?: return@get
                if (path.isBlank() || path == "/") {
                    path = "index.html" // Default to index.html for root
                }
                val assetPath = "web/$path"
                try {
                    service.applicationContext.assets.open(assetPath).use { inputStream ->
                        val contentType = ContentType.fromFileExtension(path).firstOrNull()
                            ?: ContentType.Application.OctetStream
                        call.respondOutputStream(contentType) {
                            inputStream.copyTo(this)
                        }
                    }
                } catch (_: IOException) {
                    call.respond(HttpStatusCode.NotFound, "File not found: $assetPath")
                }
            }
        }
    }

    fun start(): Boolean {
        try {
            logMessageCallback("Attempting to start LocalHttpServer...")
            server.start(wait = false)
            logMessageCallback("LocalHttpServer started successfully on port $PORT.")
            return true
        } catch (_: BindException) {
            logMessageCallback("Port $PORT is already in use. Please close the other application and restart the service.")
            return false
        } catch (e: Exception) {
            logMessageCallback("An unexpected error occurred while starting the LocalHttpServer: ${e.message}")
            Log.e("LocalHttpServer", "Start failed", e)
            return false
        }
    }

    fun stop() {
        server.stop(1000, 1000)
        httpClient.close()
    }

    /**
     * Receives a request from a peer and dispatches it to the local Ktor server.
     */
    suspend fun dispatchRequest(wrapper: HttpRequestWrapper) {
        // Prevent re-dispatching a request that originated from this node
        if (wrapper.sourceNodeId == service.getEndpointName()) {
            logMessageCallback("Not dispatching own request: ${wrapper.path}")
            return
        }

        val url = if (wrapper.queryParams.isNotEmpty()) {
            "http://localhost:$PORT${wrapper.path}?sourceNodeId=${wrapper.sourceNodeId}&${wrapper.queryParams}"
        } else {
            "http://localhost:$PORT${wrapper.path}?sourceNodeId=${wrapper.sourceNodeId}"
        }

        logMessageCallback("Dispatching request from ${wrapper.sourceNodeId}: ${wrapper.method} $url")

        try {
            val response = httpClient.request(url) {
                method = HttpMethod.parse(wrapper.method)
                if (method == HttpMethod.Post && wrapper.body.isNotEmpty()) {
                    setBody(TextContent(wrapper.body, ContentType.Application.FormUrlEncoded))
                }
            }
            logMessageCallback("Dispatched request '${wrapper.path}' from '${wrapper.sourceNodeId}' completed with status: ${response.status}")
        } catch (e: Exception) {
            logMessageCallback("Error dispatching request from '${wrapper.sourceNodeId}': ${e.message}")
            Log.e("LocalHttpServer", "Dispatch failed", e)
        }
    }

    companion object {
        const val PORT = 8099

        // Attribute to cache the request body
        val RequestBodyAttribute = AttributeKey<String>("RequestBodyAttribute")
    }
}