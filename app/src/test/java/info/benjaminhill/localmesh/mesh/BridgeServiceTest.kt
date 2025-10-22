package info.benjaminhill.localmesh.mesh

import info.benjaminhill.localmesh.LocalHttpServer
import info.benjaminhill.localmesh.logic.FileChunk
import info.benjaminhill.localmesh.logic.HttpRequestWrapper
import info.benjaminhill.localmesh.logic.NetworkMessage
import info.benjaminhill.localmesh.util.AppLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.powermock.reflect.Whitebox
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the `BridgeService` class.
 * This suite verifies the core logic of the gossip protocol implementation in `BridgeService`,
 * including message processing, duplicate detection, and forwarding.
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

    @Before
    fun setUp() {
        // Mock all dependencies to isolate the BridgeService logic
        mockNearbyConnectionsManager = mock()
        mockLocalHttpServer = mock()
        mockServiceHardener = mock()
        mockFileReassemblyManager = mock()

        // Create the service instance and inject mocks
        service = BridgeService()
        service.nearbyConnectionsManager = mockNearbyConnectionsManager
        service.localHttpServer = mockLocalHttpServer
        service.serviceHardener = mockServiceHardener
        service.ioDispatcher = testDispatcher
        service.endpointName = "test-endpoint"
        Whitebox.setInternalState(service, "logger", mock<AppLogger>())
        Whitebox.setInternalState(service, "fileReassemblyManager", mockFileReassemblyManager)
    }

    @Test
    fun `handleIncomingData dispatches HTTP request and forwards message`() = runTest {
        // Given a network message containing an HTTP request
        val wrapper = HttpRequestWrapper(
            method = "GET",
            path = "/test",
            queryParams = "foo=bar",
            body = "",
            sourceNodeId = "remote-node"
        )
        val networkMessage = NetworkMessage(
            messageId = "test-message-id",
            httpRequest = wrapper
        )
        val jsonString = Json.encodeToString(NetworkMessage.serializer(), networkMessage)
        val payloadBytes = jsonString.toByteArray(Charsets.UTF_8)

        // When the service handles the incoming data
        service.handleIncomingData("from-endpoint", payloadBytes)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then the service hardener is notified
        verify(mockServiceHardener).updateP2pMessageTime()
        // And the HTTP request is dispatched to the local server
        verify(mockLocalHttpServer).dispatchRequest(wrapper)
        // And the message is forwarded to other peers
        verify(mockNearbyConnectionsManager).sendPayload(any<List<String>>(), any<ByteArray>())
    }

    @Test
    fun `handleIncomingData adds file chunk and forwards message`() = runTest {
        // Given a network message containing a file chunk
        val fileChunk = FileChunk(
            fileId = "file-123",
            destinationPath = "test.txt",
            chunkIndex = 0,
            totalChunks = 1,
            data = byteArrayOf(1, 2, 3)
        )
        val networkMessage = NetworkMessage(
            messageId = "test-message-id",
            fileChunk = fileChunk
        )
        val jsonString = Json.encodeToString(NetworkMessage.serializer(), networkMessage)
        val payloadBytes = jsonString.toByteArray(Charsets.UTF_8)

        // When the service handles the incoming data
        service.handleIncomingData("from-endpoint", payloadBytes)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then the service hardener is notified
        verify(mockServiceHardener).updateP2pMessageTime()
        // And the file chunk is added to the reassembly manager
        verify(mockFileReassemblyManager).addChunk(fileChunk)
        // And the message is forwarded to other peers
        verify(mockNearbyConnectionsManager).sendPayload(any<List<String>>(), any<ByteArray>())
    }

    @Test
    fun `handleIncomingData ignores and does not forward seen messages`() = runTest {
        // Given a network message
        val networkMessage = NetworkMessage(
            messageId = "seen-message-id",
            httpRequest = HttpRequestWrapper("GET", "/test", "", "", "remote")
        )
        val jsonString = Json.encodeToString(NetworkMessage.serializer(), networkMessage)
        val payloadBytes = jsonString.toByteArray(Charsets.UTF_8)

        // When the service handles the same message twice
        service.handleIncomingData("from-endpoint-1", payloadBytes)
        service.handleIncomingData("from-endpoint-2", payloadBytes)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then the HTTP request is only dispatched once
        verify(mockLocalHttpServer).dispatchRequest(any())
        // And the message is only forwarded once
        verify(mockNearbyConnectionsManager).sendPayload(any<List<String>>(), any<ByteArray>())
    }
}
