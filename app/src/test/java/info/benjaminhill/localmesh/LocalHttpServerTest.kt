package info.benjaminhill.localmesh

import android.content.Context
import info.benjaminhill.localmesh.mesh.BridgeService
import info.benjaminhill.localmesh.mesh.BridgeState
import info.benjaminhill.localmesh.mesh.NearbyConnectionsManager
import info.benjaminhill.localmesh.util.AppLogger
import info.benjaminhill.localmesh.util.AssetManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tests the `LocalHttpServer` class.
 * This class tests the ability of the `LocalHttpServer` to serve static files,
 * handle API requests, and broadcast messages to peers. This class does not
 * test the P2P communication itself, but rather the handling of the HTTP
 * requests and responses. This class uses a mock
 * `BridgeService` to test the `LocalHttpServer`.
 */
@RunWith(RobolectricTestRunner::class)
class LocalHttpServerTest {

    // JUnit rule to create a temporary folder for each test, ensuring cleanup.
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: LocalHttpServer
    private lateinit var mockBridgeService: BridgeService
    private lateinit var mockNearbyConnectionsManager: NearbyConnectionsManager
    private lateinit var mockLogger: AppLogger

    @Before
    fun setUp() {
        mockNearbyConnectionsManager = mock()
        mockBridgeService = mock {
            on { endpointName } doReturn "test-endpoint"
            on { currentState } doReturn BridgeState.Running
            on { nearbyConnectionsManager } doReturn mockNearbyConnectionsManager
        }
        mockLogger = mock()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) {
            server.stop()
        }
    }

    @Test
    fun `GET status returns correct peer count`() = runBlocking {
        // Given
        val mockContext: Context = mock {
            on { filesDir } doReturn tempFolder.newFolder()
        }
        doReturn(mockContext).`when`(mockBridgeService).applicationContext
        server = LocalHttpServer(mockBridgeService, mockLogger)
        server.start()
        val mockPeers = setOf("peer1", "peer2", "peer3")
        val mockPeerFlow = MutableStateFlow(mockPeers)
        doReturn(mockPeerFlow).`when`(mockNearbyConnectionsManager).connectedPeers

        // When
        val client = HttpClient(CIO)
        val response = client.get("http://localhost:${LocalHttpServer.PORT}/status")
        val responseBody = response.bodyAsText()
        val statusResponse = Json.decodeFromString<StatusResponse>(responseBody)

        // Then
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("test-endpoint", statusResponse.id)
        assertEquals(3, statusResponse.peerCount)
        assertEquals(listOf("peer1", "peer2", "peer3"), statusResponse.peerIds.sorted())
    }

    @Test
    fun `POST chat broadcasts to peers`() = runBlocking {
        // Given
        val mockContext: Context = mock {
            on { filesDir } doReturn tempFolder.newFolder()
        }
        doReturn(mockContext).`when`(mockBridgeService).applicationContext
        server = LocalHttpServer(mockBridgeService, mockLogger)
        server.start()
        val client = HttpClient(CIO)
        val message = "Hello, peers!"

        // When
        val response = client.post("http://localhost:${LocalHttpServer.PORT}/chat") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("message=$message")
        }

        // Then
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Request broadcasted to peers.", response.bodyAsText())

        // Verify that the broadcast method was called on the bridge service
        verify(mockBridgeService).broadcast(any())

        client.close()
    }

    @Test
    fun `GET from root serves index_html`() = runBlocking {
        // Given
        val client = HttpClient(CIO)
        val tempDir = tempFolder.newFolder()
        val webCacheDir = File(tempDir, AssetManager.UNPACKED_FILES_DIR)
        webCacheDir.mkdirs()
        val indexFile = File(webCacheDir, "index.html")
        val fileContent = "<html><body><h1>Hello, World!</h1></body></html>"
        indexFile.writeText(fileContent)

        // Mock the context to return our temporary directory
        val mockContext: Context = mock {
            on { filesDir } doReturn tempDir
        }
        doReturn(mockContext).`when`(mockBridgeService).applicationContext

        server = LocalHttpServer(mockBridgeService, mockLogger)
        server.start()

        // When
        val response = client.get("http://localhost:${LocalHttpServer.PORT}/")
        val responseBody = response.bodyAsText()

        // Then
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(fileContent, responseBody)

        client.close()
    }

    @Test
    fun `GET specific file serves that file`() = runBlocking {
        // Given
        val client = HttpClient(CIO)
        val tempDir = tempFolder.newFolder()
        val webCacheDir = File(tempDir, AssetManager.UNPACKED_FILES_DIR)
        webCacheDir.mkdirs()
        val specificFile = File(webCacheDir, "test.txt")
        val fileContent = "This is a test file."
        specificFile.writeText(fileContent)

        // Mock the context to return our temporary directory
        val mockContext: Context = mock {
            on { filesDir } doReturn tempDir
        }
        doReturn(mockContext).`when`(mockBridgeService).applicationContext

        server = LocalHttpServer(mockBridgeService, mockLogger)
        server.start()

        // When
        val response = client.get("http://localhost:${LocalHttpServer.PORT}/test.txt")
        val responseBody = response.bodyAsText()

        // Then
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(fileContent, responseBody)

        client.close()
    }

    @Test
    fun `GET non-existent file returns 404`() = runBlocking {
        // Given
        val mockContext: Context = mock {
            on { filesDir } doReturn tempFolder.newFolder()
        }
        doReturn(mockContext).`when`(mockBridgeService).applicationContext
        server = LocalHttpServer(mockBridgeService, mockLogger)
        server.start()
        val client = HttpClient(CIO)

        // When
        val response = client.get("http://localhost:${LocalHttpServer.PORT}/non-existent-file.txt")

        // Then
        assertEquals(HttpStatusCode.NotFound, response.status)

        client.close()
    }

    @Test
    fun `GET folders returns list of directories`() = runBlocking {
        // Given
        val client = HttpClient(CIO)
        val tempDir = tempFolder.newFolder()
        val webCacheDir = File(tempDir, "web") // Using "web" to match UNPACKED_FILES_DIR
        webCacheDir.mkdirs()
        File(webCacheDir, "folder1").mkdir()
        File(webCacheDir, "folder2").mkdir()
        File(webCacheDir, "file.txt").createNewFile() // Should be ignored

        // Mock the context to return our temporary directory
        val mockContext: Context = mock {
            on { filesDir } doReturn tempDir
        }
        doReturn(mockContext).`when`(mockBridgeService).applicationContext

        server = LocalHttpServer(mockBridgeService, mockLogger)
        server.start()

        // When
        val response = client.get("http://localhost:${LocalHttpServer.PORT}/folders")
        val responseBody = response.bodyAsText()
        val folderList = Json.decodeFromString<List<String>>(responseBody)

        // Then
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(folderList.contains("folder1"))
        assertTrue(folderList.contains("folder2"))
        assertEquals(2, folderList.size)

        client.close()
    }

    @Test
    fun `GET css file has correct content type`() = runBlocking {
        // Given
        val client = HttpClient(CIO)
        val tempDir = tempFolder.newFolder()
        val webCacheDir = File(tempDir, AssetManager.UNPACKED_FILES_DIR)
        webCacheDir.mkdirs()
        val eyeDir = File(webCacheDir, "eye")
        eyeDir.mkdirs()
        val cssFile = File(eyeDir, "icuween.css")
        val fileContent = "body { background-color: #000; }"
        cssFile.writeText(fileContent)

        // Mock the context to return our temporary directory
        val mockContext: Context = mock {
            on { filesDir } doReturn tempDir
        }
        doReturn(mockContext).`when`(mockBridgeService).applicationContext

        server = LocalHttpServer(mockBridgeService, mockLogger)
        server.start()

        // When
        val response = client.get("http://localhost:${LocalHttpServer.PORT}/eye/icuween.css")

        // Then
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"]?.startsWith(ContentType.Text.CSS.toString()) ?: false)

        client.close()
    }
}

