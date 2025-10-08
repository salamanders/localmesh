package info.benjaminhill.localmesh

import info.benjaminhill.localmesh.mesh.BridgeService
import info.benjaminhill.localmesh.mesh.HttpRequestWrapper
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LocalHttpServerTest {

    private lateinit var mockBridgeService: BridgeService
    private lateinit var localHttpServer: LocalHttpServer

    @Before
    fun setUp() {
        mockBridgeService = mock()
        whenever(mockBridgeService.getEndpointName()).thenReturn("test-node")
        localHttpServer = LocalHttpServer(mockBridgeService) { /* No-op logger */ }
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
        assertEquals("param1=value1", broadcastRequest.params)
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
        assertEquals("&field1=data1&field2=data2", broadcastRequest.params)
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
        assertEquals("query=qval&body=bval", broadcastRequest.params)
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
}
