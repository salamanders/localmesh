package info.benjaminhill.localmesh.display

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import info.benjaminhill.localmesh.LocalHttpServer
import info.benjaminhill.localmesh.logic.FileChunk
import info.benjaminhill.localmesh.logic.NetworkMessage
import info.benjaminhill.localmesh.mesh.BridgeService
import info.benjaminhill.localmesh.mesh.FileReassemblyManager
import info.benjaminhill.localmesh.mesh.NearbyConnectionsManager
import info.benjaminhill.localmesh.util.AssetManager
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
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

        // 2. Replace the real NearbyConnectionsManager and FileReassemblyManager with mocks
        // to isolate the test from actual P2P communication and file reassembly logic.
        mockNearbyConnectionsManager = mock()
        val ncmField = BridgeService::class.java.getDeclaredField("nearbyConnectionsManager")
        ncmField.isAccessible = true
        ncmField.set(bridgeService, mockNearbyConnectionsManager)

        val frmField = BridgeService::class.java.getDeclaredField("fileReassemblyManager")
        frmField.isAccessible = true
        frmField.set(bridgeService, FileReassemblyManager(bridgeService, mock()))

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
        val destinationPath = "test/index.html"
        val fileContent = tempFileToSend.readBytes()

        // 1. Create a FileChunk for a single-chunk file.
        val fileChunk = FileChunk(
            fileId = "test-file-id",
            destinationPath = destinationPath,
            chunkIndex = 0,
            totalChunks = 1,
            data = fileContent
        )
        // 2. Wrap it in a NetworkMessage.
        val networkMessage = NetworkMessage(fileChunk = fileChunk)
        val payloadBytes = Json.encodeToString(networkMessage).toByteArray(Charset.defaultCharset())

        // 3. Call the handler on the *real* BridgeService to simulate the payload arriving.
        // This will trigger the FileReassemblyManager to save the file.
        bridgeService.handleIncomingData("fake-peer-id", payloadBytes)

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