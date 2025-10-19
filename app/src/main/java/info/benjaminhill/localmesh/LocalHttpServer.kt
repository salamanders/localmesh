package info.benjaminhill.localmesh

import android.content.Intent
import info.benjaminhill.localmesh.display.DisplayActivity
import info.benjaminhill.localmesh.logic.HttpRequestWrapper
import info.benjaminhill.localmesh.mesh.BridgeService
import info.benjaminhill.localmesh.util.AppLogger
import info.benjaminhill.localmesh.util.AssetManager
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
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.Serializable
import java.io.File
import io.ktor.server.cio.CIO as KtorCIO


// A Ktor plugin to prevent browser caching of served files.
// Ensures the latest version of the UI is always displayed.
val NoCachePlugin = createApplicationPlugin(name = "NoCachePlugin") {
    onCall { call ->
        call.response.header("Cache-Control", "no-cache, no-store, must-revalidate")
        call.response.header("Pragma", "no-cache")
        call.response.header("Expires", "0")
    }
}

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
    private val logger: AppLogger,
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
                logger.log("Broadcasting to peers: $wrapper")
                service.broadcast(wrapper)

                // Stop the pipeline for this request by responding immediately.
                // This prevents the controller from executing the command on the originating device,
                // which is the desired behavior for broadcast-only commands like /display.
                call.respond(HttpStatusCode.OK, "Request broadcasted to peers.")
            }
        }

    private lateinit var server: io.ktor.server.engine.EmbeddedServer<io.ktor.server.cio.CIOApplicationEngine, io.ktor.server.cio.CIOApplicationEngine.Configuration>

    fun start(): Boolean = logger.runCatchingWithLogging {
        logger.log("Attempting to start LocalHttpServer...")
        server = embeddedServer(KtorCIO, port = PORT) {
            install(NoCachePlugin)
            // Automatically handle JSON serialization/deserialization for API endpoints.
            install(ContentNegotiation) {
                json()
            }
            // Enable support for HTTP Range requests, essential for video streaming.
            install(PartialContent)
            install(p2pBroadcastInterceptor)
            // Centralized error handling to catch and log any unhandled exceptions.
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    logger.e("Unhandled Ktor error on ${call.request.path()}", cause)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        "Internal Server Error: ${cause.message ?: "Unknown error"}"
                    )
                }
            }
            install(createApplicationPlugin(name = "RedirectFix") {
                onCall { call ->
                    val path = call.request.path().trim('/')
                    AssetManager.getRedirectPath(service.applicationContext, path)
                        ?.let { redirectPath ->
                            logger.log("RedirectFix: raw path: \"${call.request.path()}\" -> new path: \"$redirectPath\"")
                            call.respondRedirect("/$redirectPath")
                        }
                }
            })
            routing {
                get("/folders") {
                    call.respond(AssetManager.getFolders(service.applicationContext))
                }
                get("/status") {
                    call.respond(
                        StatusResponse(
                            status = service.currentState.javaClass.simpleName,
                            id = service.endpointName,
                            peerCount = service.nearbyConnectionsManager.connectedPeers.value.size,
                            peerIds = service.nearbyConnectionsManager.connectedPeers.value.toList()
                        )
                    )
                }
                post("/chat") {
                    val params = call.receiveParameters()
                    val message = params["message"] ?: "[empty]"
                    val source = call.request.queryParameters["sourceNodeId"] ?: "local"
                    logger.log("Chat from $source: $message")
                    call.respond(mapOf("status" to "OK"))
                }
                get("/display") {
                    val path = call.parameters["path"]
                    if (path == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing path parameter")
                        return@get
                    }
                    val intent =
                        Intent(service.applicationContext, DisplayActivity::class.java).apply {
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
                post("/send-file") {
                    val sourceNodeId = call.request.queryParameters["sourceNodeId"]
                    if (sourceNodeId != null) {
                        val filename = call.request.queryParameters["filename"] ?: "unknown"
                        val payloadId = call.request.queryParameters["payloadId"] ?: "unknown"
                        logger.log("Received file request for '$filename' (payloadId: $payloadId) from peer '$sourceNodeId'. Awaiting stream.")
                        call.respond(HttpStatusCode.OK, "File request acknowledged from peer.")
                        return@post
                    }
                    val multipart = call.receiveMultipart()
                    var responseSent = false
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            val destinationPath = part.originalFileName ?: "unknown.bin"
                            val tempFile = File(
                                service.cacheDir,
                                "upload_temp_${System.currentTimeMillis()}_${
                                    destinationPath.replace(
                                        '/',
                                        '_'
                                    )
                                }"
                            )
                            part.provider().toInputStream().use { its ->
                                tempFile.outputStream().use { fos ->
                                    its.copyTo(fos)
                                }
                                service.sendFile(tempFile, destinationPath)
                                AssetManager.saveFile(
                                    service.applicationContext,
                                    destinationPath,
                                    tempFile.inputStream()
                                )
                            }
                            tempFile.delete()
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
                post("/file-received") {
                    val params = call.receiveParameters()
                    val filename = params["filename"] ?: "unknown"
                    val source = call.request.queryParameters["sourceNodeId"] ?: "local"
                    logger.log("Notification: File '$filename' was successfully received from $source.")
                    call.respond(mapOf("status" to HttpStatusCode.OK))
                }
                logger.log("Serving static files from: ${AssetManager.getFilesDir(service.applicationContext).absolutePath}")
                staticFiles(
                    "/",
                    AssetManager.getFilesDir(service.applicationContext)
                ) {
                    default("index.html")
                }
            }
        }.start(wait = false)
        logger.log("LocalHttpServer started successfully on port $PORT.")
        true
    } ?: false

    fun stop() {
        logger.log("Stopping Ktor server.")
        if (this::server.isInitialized) {
            server.stop(1000, 1000)
        }
        httpClient.close()
    }

    /**
     * Receives a request from a peer and dispatches it to the local Ktor server.
     */
    suspend fun dispatchRequest(wrapper: HttpRequestWrapper) =
        logger.runCatchingWithLogging {
            // Prevent re-dispatching a request that originated from this node
            if (wrapper.sourceNodeId == service.endpointName) {
                logger.log("Not dispatching own request: ${wrapper.path}")
                return@runCatchingWithLogging
            }

            val url = if (wrapper.queryParams.isNotEmpty()) {
                "http://localhost:$PORT${wrapper.path}?sourceNodeId=${wrapper.sourceNodeId}&${wrapper.queryParams}"
            } else {
                "http://localhost:$PORT${wrapper.path}?sourceNodeId=${wrapper.sourceNodeId}"
            }

            logger.log("Dispatching request from ${wrapper.sourceNodeId}: ${wrapper.method} $url")

            val response = httpClient.request(url) {
                method = HttpMethod.parse(wrapper.method)
                if (method == HttpMethod.Post && wrapper.body.isNotEmpty()) {
                    setBody(TextContent(wrapper.body, ContentType.Application.FormUrlEncoded))
                }
            }
            logger.log("Dispatched request '${wrapper.path}' from '${wrapper.sourceNodeId}' completed with status: ${response.status}")
        }

    companion object {
        const val PORT = 8099
        private val BROADCAST_PATHS = setOf("/chat", "/display")
    }
}
