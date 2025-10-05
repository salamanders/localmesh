package info.benjaminhill.localmesh

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.nearby.connection.Payload
import info.benjaminhill.localmesh.mesh.P2PBridgeService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class P2PBridgeServiceTest {

    private lateinit var service: P2PBridgeService
    private lateinit var context: Context

    @Before
    fun setUp() {
        service = Robolectric.buildService(P2PBridgeService::class.java).create().get()
        context = ApplicationProvider.getApplicationContext()
    }

    /**
     * Uses reflection to extract the `payloadReceivedCallback` lambda from the private `nearbyConnectionsManager`.
     * This allows testing the payload handling logic without a full P2P connection.
     */
    private fun getPayloadReceivedCallbackLambda(service: P2PBridgeService): (String, Payload) -> Unit {
        val nearbyConnectionsManagerField = service.javaClass.getDeclaredField("nearbyConnectionsManager")
        nearbyConnectionsManagerField.isAccessible = true
        val nearbyConnectionsManager = nearbyConnectionsManagerField.get(service)

        // The lambda is passed as a constructor parameter, so it becomes a private field.
        val payloadReceivedCallbackField = nearbyConnectionsManager.javaClass.getDeclaredField("payloadReceivedCallback")
        payloadReceivedCallbackField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return payloadReceivedCallbackField.get(nearbyConnectionsManager) as (String, Payload) -> Unit
    }

    @Test
    fun `handle 'content' command and cache file`() {
        val fileName = "disco.html"
        val fileContent = "<h1>The roof is on fire</h1>"
        val message = P2PBridgeService.Message("sender", 1L, 1L, "content $fileName $fileContent")
        val jsonString = kotlinx.serialization.json.Json.encodeToString(P2PBridgeService.Message.serializer(), message)
        val payload = Payload.fromBytes(jsonString.toByteArray())

        val payloadReceivedCallback = getPayloadReceivedCallbackLambda(service)
        payloadReceivedCallback("fakeEndpointId", payload)

        val cacheDir = File(context.cacheDir, "web_cache")
        val cachedFile = File(cacheDir, fileName)
        assertTrue("File should be cached", cachedFile.exists())
        assertEquals("File content should match", fileContent, cachedFile.readText())
    }

    @Test
    fun `handle 'display' command and launch WebViewActivity`() {
        val urlPath = "disco.html"
        val message = P2PBridgeService.Message("sender", 1L, 1L, "display $urlPath")
        val jsonString = kotlinx.serialization.json.Json.encodeToString(P2PBridgeService.Message.serializer(), message)
        val payload = Payload.fromBytes(jsonString.toByteArray())

        val payloadReceivedCallback = getPayloadReceivedCallbackLambda(service)
        payloadReceivedCallback("fakeEndpointId", payload)

        val shadowApp = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
        val nextStartedActivity = shadowApp.nextStartedActivity
        assertNotNull("Expected an activity to be started", nextStartedActivity)

        val expectedUrl = "http://localhost:${LocalHttpServer.PORT}/$urlPath"
        assertEquals(WebViewActivity::class.java.name, nextStartedActivity.component?.className)
        assertEquals(expectedUrl, nextStartedActivity.getStringExtra(WebViewActivity.EXTRA_URL))
    }
}