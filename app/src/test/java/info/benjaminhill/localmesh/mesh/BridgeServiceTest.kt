package info.benjaminhill.localmesh.mesh

import info.benjaminhill.localmesh.LocalHttpServer
import info.benjaminhill.localmesh.logic.FileChunk
import info.benjaminhill.localmesh.logic.HttpRequestWrapper
import info.benjaminhill.localmesh.logic.NetworkMessage
import info.benjaminhill.localmesh.util.AppLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.powermock.reflect.Whitebox
import org.robolectric.RobolectricTestRunner
import java.util.Arrays

/**
 * Tests the `BridgeService`'s ability to coordinate the gossip protocol.
 * - Verifies that incoming messages are correctly processed (dispatching HTTP requests, handling file chunks).
 * - Verifies that messages are forwarded to other peers according to the gossip rules.
 * - Mocks all major dependencies (`NearbyConnectionsManager`, `LocalHttpServer`, etc.) to isolate the `BridgeService` logic.
 */
@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class BridgeServiceTest {

    private lateinit var service: BridgeService
    private lateinit var mockNearbyConnectionsManager: NearbyConnectionsManager
    private lateinit var mockLocalHttpServer: LocalHttpServer
    private lateinit var mockServiceHardener: ServiceHardener
    private lateinit var mockFileReassemblyManager: FileReassemblyManager
    private val testDispatcher = StandardTestDispatcher()

    private val connectedPeersFlow = MutableStateFlow(emptySet<String>())

    @Before
    fun setUp() {
        mockNearbyConnectionsManager = mock()
        mockLocalHttpServer = mock()
        mockServiceHardener = mock()
        mockFileReassemblyManager = mock()

        whenever(mockNearbyConnectionsManager.connectedPeers).thenReturn(connectedPeersFlow)

        service = BridgeService().apply {
            nearbyConnectionsManager = mockNearbyConnectionsManager
            localHttpServer = mockLocalHttpServer
            serviceHardener = mockServiceHardener
            fileReassemblyManager = mockFileReassemblyManager
            ioDispatcher = testDispatcher
            endpointName = "self"
        }
        Whitebox.setInternalState(service, "logger", mock<AppLogger>())
    }

    @Test
    fun `handleIncomingData processes and forwards http requests`() = runTest {
        val originEndpoint = "endpoint-A"
        connectedPeersFlow.value = setOf(originEndpoint, "endpoint-B", "endpoint-C")

        val wrapper = HttpRequestWrapper(method = "GET", path = "/test", queryParams = "", body = "", sourceNodeId = "remote-node")
        val originalMessage = NetworkMessage(httpRequest = wrapper)
        val payload = Json.encodeToString(NetworkMessage.serializer(), originalMessage)
            .toByteArray(Charsets.UTF_8)

        service.handleIncomingData(originEndpoint, payload)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockServiceHardener).updateP2pMessageTime()
        verify(mockLocalHttpServer).dispatchRequest(wrapper)

        val expectedForwardPeers = listOf("endpoint-B", "endpoint-C")
        val expectedForwardedMessage = originalMessage.copy(hopCount = 1)
        val expectedPayload = Json.encodeToString(NetworkMessage.serializer(), expectedForwardedMessage)
            .toByteArray(Charsets.UTF_8)

        val payloadCaptor = argumentCaptor<ByteArray>()
        verify(mockNearbyConnectionsManager).sendPayload(eq(expectedForwardPeers), payloadCaptor.capture())
        assertTrue(Arrays.equals(expectedPayload, payloadCaptor.firstValue))
    }

    @Test
    fun `handleIncomingData processes file chunks`() = runTest {
        val originEndpoint = "endpoint-A"
        val fileChunk = FileChunk("file1", "path/to/file.txt", 0, 1, byteArrayOf(1, 2, 3))
        val networkMessage = NetworkMessage(fileChunk = fileChunk)
        val payload = Json.encodeToString(NetworkMessage.serializer(), networkMessage)
            .toByteArray(Charsets.UTF_8)

        service.handleIncomingData(originEndpoint, payload)
        testDispatcher.scheduler.advanceUntilIdle()

        val chunkCaptor = argumentCaptor<FileChunk>()
        verify(mockFileReassemblyManager).handleFileChunk(chunkCaptor.capture())
        val capturedChunk = chunkCaptor.firstValue
        assertTrue(capturedChunk.fileId == fileChunk.fileId && capturedChunk.data.contentEquals(fileChunk.data))
    }

    @Test
    fun `handleIncomingData ignores seen messages`() = runTest {
        val originEndpoint = "endpoint-A"
        connectedPeersFlow.value = setOf(originEndpoint, "endpoint-B")

        val wrapper = HttpRequestWrapper(method = "GET", path = "/test", queryParams = "", body = "", sourceNodeId = "remote-node")
        val networkMessage = NetworkMessage(httpRequest = wrapper)
        val payload = Json.encodeToString(NetworkMessage.serializer(), networkMessage)
            .toByteArray(Charsets.UTF_8)

        service.handleIncomingData(originEndpoint, payload)
        service.handleIncomingData(originEndpoint, payload)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockLocalHttpServer, times(1)).dispatchRequest(any())
        verify(mockNearbyConnectionsManager, times(1)).sendPayload(any<List<String>>(), any<ByteArray>())
    }
}
