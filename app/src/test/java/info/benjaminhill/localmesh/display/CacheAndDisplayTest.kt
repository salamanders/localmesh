package info.benjaminhill.localmesh.display

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.nearby.connection.Payload
import info.benjaminhill.localmesh.LocalHttpServer
import info.benjaminhill.localmesh.logic.HttpRequestWrapper
import info.benjaminhill.localmesh.mesh.BridgeService
import info.benjaminhill.localmesh.mesh.NearbyConnectionsManager
import info.benjaminhill.localmesh.util.AssetManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.charset.Charset

/**
 * Tests the file caching and display functionality of the application.
 * This class tests the ability of the application to receive a file from a peer,
 * cache it to the local file system, and then serve it via the local HTTP server.
 * This class does not test the P2P communication itself, but rather the handling
 * of the file after it has been received. It is surprising that this class uses
 * Robolectric to create a real `BridgeService` instance.
 */
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

        // Ensure assets are unpacked for the server to serve them
        AssetManager.unpack(ApplicationProvider.getApplicationContext())

        // 2. Replace the real NearbyConnectionsManager with a mock to prevent network calls
        // This is necessary because Robolectric creates a real service, but we want to
        // isolate the test from actual P2P communication.
        mockNearbyConnectionsManager = mock()
        val ncmField = BridgeService::class.java.getDeclaredField("nearbyConnectionsManager")
        ncmField.isAccessible = true // Allow modification of a private field
        ncmField.set(bridgeService, mockNearbyConnectionsManager)

        // 3. Get a reference to the LocalHttpServer created by the service
        localHttpServer = bridgeService.localHttpServer
        // Manually ensure the service is started for the test
        localHttpServer.start()
        bridgeService.onStartCommand(Intent(BridgeService.Companion.ACTION_START), 0, 0)

        // Wait for the server to be ready before proceeding with the test
        runBlocking {
            var connected = false
            val client = HttpClient(CIO)
            for (i in 1..10) {
                try {
                    System.out.println("CacheAndDisplayTest: Attempting to connect to server, attempt $i...")
                    // Use a lightweight request to check server status
                    client.get("http://localhost:${LocalHttpServer.PORT}/status")
                    connected = true
                    System.out.println("CacheAndDisplayTest: Successfully connected to server.")
                    break
                } catch (e: Exception) {
                    System.err.println("CacheAndDisplayTest: Connection attempt $i failed: ${e.message}")
                    if (i == 10) throw e // rethrow last exception
                    delay(200)
                }
            }
            client.close()
            Assert.assertTrue("Failed to connect to the local HTTP server.", connected)
        }

        // 4. Define the cache directory and create a temporary file to "receive"
        val context: Context = ApplicationProvider.getApplicationContext()
        webCacheDir = File(context.filesDir, AssetManager.UNPACKED_FILES_DIR)
        webCacheDir.mkdirs()

        val content = "<html><body>Hello, Cached World!</body></html>"
        tempFileToSend = File.createTempFile("test-", ".html").apply {
            writeText(content)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            localHttpServer.stop()
            delay(500) // Give the OS time to release the port
        }
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
        bridgeService.handleIncomingData(bytesPayload.asBytes()!!) // Primes the service
        bridgeService.handleStreamPayload(streamPayload) // Delivers the file data

        // --- Verify the file is cached and can be displayed ---
        val cachedFile = File(webCacheDir, destinationPath)
        Assert.assertTrue("File was not cached to the correct location.", cachedFile.exists())
        Assert.assertEquals(
            "Cached file content does not match original.",
            tempFileToSend.readText(),
            cachedFile.readText()
        )

        // Simulate a "display" by making an HTTP GET request to the local server
        val client = HttpClient(CIO)
        val response: HttpResponse =
            client.get("http://localhost:${LocalHttpServer.Companion.PORT}/test/index.html")
        val responseBody = response.bodyAsText()

        Assert.assertEquals(
            "HTTP response body did not match the file content.",
            tempFileToSend.readText(),
            responseBody
        )
        client.close()
    }
}