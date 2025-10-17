package info.benjaminhill.localmesh.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the `HttpRequestWrapper` class.
 * This class tests the ability of the `HttpRequestWrapper` to be serialized to JSON
 * and then deserialized back to an `HttpRequestWrapper` object. This class does not
 * test the P2P communication itself, but rather the serialization and
 * deserialization of the `HttpRequestWrapper`. It is surprising that this class
 * is so simple.
 */
class HttpRequestWrapperTest {

    @Test
    fun serialization_deserialization_isCorrect() {
        val originalWrapper = HttpRequestWrapper(
            method = "POST",
            path = "/chat",
            queryParams = "",
            body = "message=hello",
            sourceNodeId = "test-node-123"
        )

        val jsonString = originalWrapper.toJson()

        val deserializedWrapper = HttpRequestWrapper.fromJson(jsonString)

        assertEquals(originalWrapper, deserializedWrapper)
    }
}
