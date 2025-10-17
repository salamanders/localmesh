package info.benjaminhill.localmesh.mesh

import com.google.android.gms.nearby.connection.Payload
import info.benjaminhill.localmesh.LocalHttpServer
import info.benjaminhill.localmesh.LogFileWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import info.benjaminhill.localmesh.util.AppLogger
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.powermock.reflect.Whitebox
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class BridgeServiceTest {

    private lateinit var service: BridgeService
    private lateinit var mockNearbyConnectionsManager: NearbyConnectionsManager
    private lateinit var mockLocalHttpServer: LocalHttpServer
    private lateinit var mockServiceHardener: ServiceHardener
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // Mock dependencies
        mockNearbyConnectionsManager = mock()
        mockLocalHttpServer = mock()
        mockServiceHardener = mock()

        // Create and setup the service instance
        service = BridgeService()
        service.nearbyConnectionsManager = mockNearbyConnectionsManager
        service.localHttpServer = mockLocalHttpServer
        service.serviceHardener = mockServiceHardener
        service.ioDispatcher = testDispatcher
        service.endpointName = "test-endpoint"
        Whitebox.setInternalState(service, "logger", mock<AppLogger>())
    }

    @Test
    fun handleBytesPayload_dispatchesRequest() = runTest {
        // Given
        val wrapper = HttpRequestWrapper(
            method = "GET",
            path = "/test",
            queryParams = "foo=bar",
            body = "",
            sourceNodeId = "remote-node"
        )
        val jsonString = wrapper.toJson()
        val payload = Payload.fromBytes(jsonString.toByteArray(Charsets.UTF_8))

        // When
        service.handleBytesPayload(payload)
        // Advance the dispatcher to execute the coroutine
        testDispatcher.scheduler.advanceUntilIdle()


        // Then
        verify(mockServiceHardener).updateP2pMessageTime()
        verify(mockLocalHttpServer).dispatchRequest(wrapper)
    }
}
