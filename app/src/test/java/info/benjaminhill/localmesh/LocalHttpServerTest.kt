package info.benjaminhill.localmesh

import android.content.Context
import android.content.res.AssetManager
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.fromFileExtension
import io.ktor.server.application.install
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import info.benjaminhill.localmesh.mesh.BridgeService
import info.benjaminhill.localmesh.mesh.HttpRequestWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files

class LocalHttpServerTest {

    private lateinit var mockBridgeService: BridgeService
    private lateinit var localHttpServer: LocalHttpServer

    @Before
    fun setUp() {
        mockBridgeService = mock()
        whenever(mockBridgeService.endpointName).thenReturn("test-node")
        localHttpServer =
            LocalHttpServer(mockBridgeService, { /* No-op logger */ }, { _, _ -> /* No-op err */ })
    }

    @Test
    fun `interceptor broadcasts GET request`(): Unit = testApplication {
        application {
            install(localHttpServer.p2pBroadcastInterceptor)
            routing {
                get("/chat") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        client.get("/chat?param1=value1")

        val captor = argumentCaptor<String>()
        verify(mockBridgeService).broadcast(captor.capture())

        val broadcastRequest = HttpRequestWrapper.fromJson(captor.firstValue)
        assertEquals("/chat", broadcastRequest.path)
        assertEquals("GET", broadcastRequest.method)
        assertEquals("param1=value1", broadcastRequest.queryParams)
        assertEquals("", broadcastRequest.body)
        assertEquals("test-node", broadcastRequest.sourceNodeId)
    }

    @Test
    fun `interceptor broadcasts POST request without query params`(): Unit = testApplication {
        application {
            install(localHttpServer.p2pBroadcastInterceptor)
            routing {
                post("/chat") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        client.post("/chat") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("field1=data1&field2=data2")
        }

        val captor = argumentCaptor<String>()
        verify(mockBridgeService).broadcast(captor.capture())

        val broadcastRequest = HttpRequestWrapper.fromJson(captor.firstValue)
        assertEquals("/chat", broadcastRequest.path)
        assertEquals("POST", broadcastRequest.method)
        assertEquals("", broadcastRequest.queryParams)
        assertEquals("field1=data1&field2=data2", broadcastRequest.body)
        assertEquals("test-node", broadcastRequest.sourceNodeId)
    }

    @Test
    fun `interceptor broadcasts POST request with query params and body`(): Unit = testApplication {
        application {
            install(localHttpServer.p2pBroadcastInterceptor)
            routing {
                post("/chat") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        client.post("/chat?query=qval") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("body=bval")
        }

        val captor = argumentCaptor<String>()
        verify(mockBridgeService).broadcast(captor.capture())

        val broadcastRequest = HttpRequestWrapper.fromJson(captor.firstValue)
        assertEquals("/chat", broadcastRequest.path)
        assertEquals("POST", broadcastRequest.method)
        assertEquals("query=qval", broadcastRequest.queryParams)
        assertEquals("body=bval", broadcastRequest.body)
        assertEquals("test-node", broadcastRequest.sourceNodeId)
    }


    @Test
    fun `interceptor ignores request with sourceNodeId`(): Unit = testApplication {
        application {
            install(localHttpServer.p2pBroadcastInterceptor)
            routing {
                get("/test-ignore") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        client.get("/test-ignore?sourceNodeId=another-node")

        verify(mockBridgeService, never()).broadcast(any())
    }

    @Test
    fun `serves partial content for range requests`() = testApplication {
        application {
            install(PartialContent)
            routing {
                get("/{path...}") {
                    val path = call.parameters.getAll("path")?.joinToString("/") ?: return@get
                    val cacheDir = File(mockBridgeService.applicationContext.cacheDir, "web_cache")
                    val cachedFile = File(cacheDir, path)
                    call.respondFile(cachedFile)
                }
            }
        }

        // Setup
        val tempDir = Files.createTempDirectory("test-cache").toFile()
        val webCacheDir = File(tempDir, "web_cache")
        webCacheDir.mkdir()
        val tempFile = File(webCacheDir, "test.txt")
        tempFile.writeBytes("1234567890".toByteArray())

        val mockContext: Context = mock()
        whenever(mockContext.cacheDir).thenReturn(tempDir)
        val mockAssetManager: AssetManager = mock()
        whenever(mockContext.assets).thenReturn(mockAssetManager)
        whenever(mockBridgeService.applicationContext).thenReturn(mockContext)

        // Action
        val response = client.get("/${tempFile.name}") {
            header("Range", "bytes=2-5")
        }

        // Assertions
        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals("bytes 2-5/10", response.headers["Content-Range"])
        assertEquals("3456", response.bodyAsText())

        // Cleanup
        tempDir.deleteRecursively()
    }
}
