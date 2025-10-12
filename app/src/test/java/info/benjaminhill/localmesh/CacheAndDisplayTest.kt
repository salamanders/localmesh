package info.benjaminhill.localmesh

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.nearby.connection.Payload
import info.benjaminhill.localmesh.mesh.BridgeService
import info.benjaminhill.localmesh.mesh.HttpRequestWrapper
import info.benjaminhill.localmesh.mesh.NearbyConnectionsManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.charset.Charset

@RunWith(RobolectricTestRunner::class)
class CacheAndDisplayTest {

    private lateinit var bridgeService: BridgeService
    private lateinit var localHttpServer: LocalHttpServer
    private lateinit var webCacheDir: File
    private lateinit var tempFileToSend: File
    private lateinit var mockNearbyConnectionsManager: NearbyConnectionsManager

    @Before
    fun setUp() {
        // 1. Create a real BridgeService instance using Robolectric
        bridgeService = Robolectric.setupService(BridgeService::class.java)

        // 2. Replace the real NearbyConnectionsManager with a mock to prevent network calls
        mockNearbyConnectionsManager = mock()
        val ncmField = BridgeService::class.java.getDeclaredField("nearbyConnectionsManager")
        ncmField.isAccessible = true
        ncmField.set(bridgeService, mockNearbyConnectionsManager)

        // 3. Get a reference to the LocalHttpServer created by the service
        localHttpServer = bridgeService.localHttpServer
        // Manually ensure the server is started for the test
        bridgeService.onStartCommand(Intent(BridgeService.ACTION_START), 0, 0)


        // 4. Define the cache directory and create a temporary file to "receive"
        val context: Context = ApplicationProvider.getApplicationContext()
        webCacheDir = File(context.cacheDir, "web_cache")
        webCacheDir.mkdirs()

        val content = "<html><body>Hello, Cached World!</body></html>"
        tempFileToSend = File.createTempFile("test-", ".html").apply {
            writeText(content)
        }
    }

    @After
    fun tearDown() {
        bridgeService.onStartCommand(Intent(BridgeService.ACTION_STOP), 0, 0)
        webCacheDir.deleteRecursively()
        tempFileToSend.delete()
    }

    @Test
    fun testFileCachingAndDisplay() = runBlocking {
        // --- Simulate receiving a file from a peer ---

        // 1. Create a STREAM payload from the temporary file. This assigns it a unique ID.
        val streamPayload = Payload.fromStream(tempFileToSend.inputStream())
        val payloadId = streamPayload.id
        val destinationPath = "test/index.html"

        // 2. Create the accompanying command (HttpRequestWrapper) that "arrived" as a BYTES payload.
        // This tells the BridgeService what to name the file when the stream arrives.
        val wrapper = HttpRequestWrapper(
            method = "POST",
            path = "/send-file",
            queryParams = "filename=$destinationPath&payloadId=$payloadId",
            body = "",
            sourceNodeId = "fake-peer-id"
        )
        val bytesPayload = Payload.fromBytes(wrapper.toJson().toByteArray(Charset.defaultCharset()))

        // 3. Call the handlers on the *real* BridgeService to simulate the two payloads arriving.
        bridgeService.handleBytesPayload(bytesPayload) // Primes the service
        bridgeService.handleStreamPayload(streamPayload) // Delivers the file data

        // --- Verify the file is cached and can be displayed ---
        val cachedFile = File(webCacheDir, destinationPath)
        assertTrue("File was not cached to the correct location.", cachedFile.exists())
        assertEquals(
            "Cached file content does not match original.",
            tempFileToSend.readText(),
            cachedFile.readText()
        )

        // Simulate a "display" by making an HTTP GET request to the local server
        val client = HttpClient(CIO)
        val response: HttpResponse =
            client.get("http://localhost:${LocalHttpServer.PORT}/test/index.html")
        val responseBody = response.bodyAsText()

        assertEquals(
            "HTTP response body did not match the file content.",
            tempFileToSend.readText(),
            responseBody
        )
        client.close()
    }
}
