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
        verify(mockLocalHttpServer).dispatchRequest(wrapper)
    }
}
