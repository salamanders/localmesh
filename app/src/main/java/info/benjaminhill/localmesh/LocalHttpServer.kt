package info.benjaminhill.localmesh

import android.content.Intent
import info.benjaminhill.localmesh.mesh.BridgeService
import info.benjaminhill.localmesh.mesh.HttpRequestWrapper
import info.benjaminhill.localmesh.util.AssetManager
import info.benjaminhill.localmesh.util.GlobalExceptionHandler.runCatchingWithLogging
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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.Serializable
import java.io.File
import io.ktor.server.cio.CIO as KtorCIO


@Serializable
data class StatusResponse(
    val status: String,
    val id: String,
    val peerCount: Int,
    val peerIds: List<String>
)

/**
 * A Ktor-based web server that runs on the Android device, serving the web UI and handling API requests.
 * It is the single source of truth for the application's API.
 *
 * ## What it does
 * - Serves the static web assets (HTML, CSS, JS, MP4) for the frontend.
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
    private val logMessageCallback: (String) -> Unit,
    private val logErrorCallback: (String, Throwable) -> Unit,
) {

    private val httpClient = HttpClient(CIO)

    /**
     * A Ktor plugin that intercepts local requests and broadcasts them to peers.
     * It decides whether a request should be broadcast to peers based on a predefined set of paths.
     */
    internal val p2pBroadcastInterceptor =
        createApplicationPlugin(name = "P2PBroadcastInterceptor") {
            onCall { call ->
                // --- Step 1: Check if the request should be broadcast ---
                val isFromPeer =
                    call.request.queryParameters["sourceNodeId"]?.takeUnless { it.isEmpty() } != null
                val path = call.request.path()

                if (isFromPeer || path !in BROADCAST_PATHS) {
                    // Let the request proceed normally without broadcasting.
                    return@onCall
                }

                // --- Step 2: Broadcast the request and stop local execution ---
                val queryParams = call.request.queryParameters.formUrlEncode()
                val body: String = if (call.request.httpMethod == HttpMethod.Post) {
                    call.receiveParameters().formUrlEncode()
                } else {
                    ""
                }

                // Create and broadcast the wrapper.
                val wrapper = HttpRequestWrapper(
                    method = call.request.httpMethod.value,
                    path = path,
                    queryParams = queryParams,
                    body = body,
                    sourceNodeId = service.endpointName
                )
                logMessageCallback("Broadcasting to peers: $wrapper")
                service.broadcast(wrapper.toJson())

                // Stop the pipeline for this request by responding immediately.
                // This prevents the controller from executing the command on the originating device,
                // which is the desired behavior for broadcast-only commands like /display.
                call.respond(HttpStatusCode.OK, "Request broadcasted to peers.")
            }
        }

    private val server = embeddedServer(KtorCIO, port = PORT) {
        install(ContentNegotiation) {
            json()
        }
        install(PartialContent)
        install(p2pBroadcastInterceptor)
        install(StatusPages) {
            exception<Throwable> { call: ApplicationCall, cause ->
                logErrorCallback("Unhandled Ktor error on ${call.request.path()}", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    "Internal Server Error: ${cause.message ?: "Unknown error"}"
                )
            }
        }

        install(createApplicationPlugin(name = "RedirectFix") {
            onCall { call ->
                val rawPath = call.request.path()
                logMessageCallback("RedirectFix: raw path: \"$rawPath\"")
                val path = rawPath.trimStart('/')
                logMessageCallback("RedirectFix: trimmed path: \"$path\"")
                AssetManager.getRedirectPath(service.applicationContext, path)?.let {
                    logMessageCallback("Redirecting to: \"$it\"")
                    call.respondRedirect(it)
                }
            }
        })

        routing {
            get("/folders") {
                call.respond(AssetManager.getFolders(service.applicationContext))
            }
            get("/status") {
                // A simple report of the service's status and number of peers
                call.respond(
                    StatusResponse(
                        status = service.currentState.javaClass.simpleName,
                        id = service.endpointName,
                        peerCount = service.nearbyConnectionsManager.connectedPeerCount,
                        peerIds = service.nearbyConnectionsManager.connectedPeerIds
                    )
                )
            }

            post("/chat") {
                val params = call.receiveParameters()
                val message = params["message"] ?: "[empty]"
                val source = call.request.queryParameters["sourceNodeId"] ?: "local"
                logMessageCallback("Chat from $source: $message")
                call.respond(mapOf("status" to "OK"))
            }

            get("/display") {
                val path = call.parameters["path"]
                if (path == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing path parameter")
                    return@get
                }

                val intent = Intent(service.applicationContext, DisplayActivity::class.java).apply {
                    putExtra(DisplayActivity.EXTRA_PATH, path)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }

                service.startActivity(intent)

                call.respond(
                    mapOf(
                        "status" to "OK",
                        "launched" to (intent.component?.className ?: "unknown")
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
                    val payloadId = call.request.queryParameters["payloadId"] ?: "unknown"
                    logMessageCallback("Received file request for '$filename' (payloadId: $payloadId) from peer '$sourceNodeId'. Awaiting stream.")
                    call.respond(HttpStatusCode.OK, "File request acknowledged from peer.")
                    return@post
                }

                // This is a new file upload from the local web UI
                val multipart = call.receiveMultipart()
                var responseSent = false
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        val destinationPath = part.originalFileName ?: "unknown.bin"
                        
                        val tempFile = File(
                            service.cacheDir,
                            "upload_temp_${System.currentTimeMillis()}_${destinationPath.replace('/', '_')}"
                        )
                        part.provider().toInputStream().use { its ->
                            // Save to a temporary file first
                            tempFile.outputStream().use { fos ->
                                its.copyTo(fos)
                            }
                            // Then send it to peers
                            service.sendFile(tempFile, destinationPath)
                            // And also save it locally using the new AssetManager method
                            AssetManager.saveFile(service.applicationContext, destinationPath, tempFile.inputStream())
                        }
                        tempFile.delete() // Clean up the temporary file

                        responseSent = true
                        call.respond(
                            mapOf(
                                "status" to HttpStatusCode.OK,
                                "file_status" to "file sending initiated",
                                "filename" to destinationPath
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
                val params = call.receiveParameters()
                val filename = params["filename"] ?: "unknown"
                val source = call.request.queryParameters["sourceNodeId"] ?: "local"
                logMessageCallback("Notification: File '$filename' was successfully received from $source.")
                call.respond(mapOf("status" to HttpStatusCode.OK))
            }
            
            /*
            // This was moved to the RedirectFix plugin to avoid consuming requests meant for static files.
            get("/{path...}") {
                val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                logMessageCallback("Redirect check for path: \"$path\"")
                AssetManager.getRedirectPath(service.applicationContext, path)?.let {
                    logMessageCallback("Redirecting to: \"$it\"")
                    call.respondRedirect(it)
                }
            }
            */

            // Serve all static content from the unpacked assets directory
            logMessageCallback("Serving static files from: ${AssetManager.getUnpackedFilesDir(service.applicationContext).absolutePath}")
            staticFiles(
                "/",
                AssetManager.getUnpackedFilesDir(service.applicationContext)
            ) {
                default("index.html")
            }
        }
    }

    fun start(): Boolean = runCatchingWithLogging({ msg, err ->
        logErrorCallback(msg, err ?: Exception(msg))
    }) {
        logMessageCallback("Attempting to start LocalHttpServer...")
        server.start(wait = false)
        logMessageCallback("LocalHttpServer started successfully on port $PORT.")
        true
    } ?: false

    fun stop() {
        server.stop(1000, 1000)
        httpClient.close()
    }

    /**
     * Receives a request from a peer and dispatches it to the local Ktor server.
     */
    suspend fun dispatchRequest(wrapper: HttpRequestWrapper) =
        runCatchingWithLogging({ msg, err ->
            logErrorCallback(msg, err ?: Exception(msg))
        }) {
            // Prevent re-dispatching a request that originated from this node
            if (wrapper.sourceNodeId == service.endpointName) {
                logMessageCallback("Not dispatching own request: ${wrapper.path}")
                return@runCatchingWithLogging
            }

            val url = if (wrapper.queryParams.isNotEmpty()) {
                "http://localhost:$PORT${wrapper.path}?sourceNodeId=${wrapper.sourceNodeId}&${wrapper.queryParams}"
            } else {
                "http://localhost:$PORT${wrapper.path}?sourceNodeId=${wrapper.sourceNodeId}"
            }

            logMessageCallback("Dispatching request from ${wrapper.sourceNodeId}: ${wrapper.method} $url")

            val response = httpClient.request(url) {
                method = HttpMethod.parse(wrapper.method)
                if (method == HttpMethod.Post && wrapper.body.isNotEmpty()) {
                    setBody(TextContent(wrapper.body, ContentType.Application.FormUrlEncoded))
                }
            }
            logMessageCallback("Dispatched request '${wrapper.path}' from '${wrapper.sourceNodeId}' completed with status: ${response.status}")
        }

    companion object {
        const val PORT = 8099
        private val BROADCAST_PATHS = setOf("/chat", "/display")
    }
}
