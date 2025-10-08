package info.benjaminhill.localmesh.mesh

import android.content.Intent
import com.google.android.gms.nearby.connection.Payload
import info.benjaminhill.localmesh.LocalHttpServer
import info.benjaminhill.localmesh.LogFileWriter
import info.benjaminhill.localmesh.ui.AppStateHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class BridgeServiceTest {

    private lateinit var service: BridgeService
    private lateinit var mockNearbyConnectionsManager: NearbyConnectionsManager
    private lateinit var mockLocalHttpServer: LocalHttpServer
    private lateinit var mockLogFileWriter: LogFileWriter
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // Mock dependencies
        mockNearbyConnectionsManager = mock()
        mockLocalHttpServer = mock()
        mockLogFileWriter = mock()

        // Create and setup the service instance
        service = BridgeService()
        service.nearbyConnectionsManager = mockNearbyConnectionsManager
        service.localHttpServer = mockLocalHttpServer
        service.logFileWriter = mockLogFileWriter
        service.ioDispatcher = testDispatcher
        service.endpointName = "test-endpoint"
    }

    @Test
    fun onStartCommand_withBroadcastCommand_callsBroadcast() = runTest {
        // Given
        AppStateHolder.currentState.value = BridgeState.Running
        val intent = Intent().apply {
            action = BridgeAction.BroadcastCommand::class.java.name
            putExtra(BridgeService.EXTRA_COMMAND, "display")
            putExtra(BridgeService.EXTRA_PAYLOAD, "some_path")
        }

        // When
        service.onStartCommand(intent, 0, 1)

        // Then
        val captor = argumentCaptor<ByteArray>()
        verify(mockNearbyConnectionsManager).broadcastBytes(captor.capture())
        val sentJson = captor.firstValue.toString(Charsets.UTF_8)
        val sentWrapper = HttpRequestWrapper.fromJson(sentJson)

        assert(sentWrapper.path == "/display")
        assert(sentWrapper.params == "path=some_path")
    }

    @Test
    fun handleBytesPayload_dispatchesRequest() = runTest {
        // Given
        val wrapper = HttpRequestWrapper(
            method = "GET",
            path = "/test",
            params = "foo=bar",
            sourceNodeId = "remote-node"
        )
        val jsonString = wrapper.toJson()
        val payload = Payload.fromBytes(jsonString.toByteArray(Charsets.UTF_8))

        // When
        service.handleBytesPayload(payload)
        // Advance the dispatcher to execute the coroutine
        testDispatcher.scheduler.advanceUntilIdle()


        // Then
        verify(mockLocalHttpServer).dispatchRequest(wrapper)
    }
}