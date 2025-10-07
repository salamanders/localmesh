package info.benjaminhill.localmesh.mesh

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpRequestWrapperTest {

    @Test
    fun serialization_deserialization_isCorrect() {
        val originalWrapper = HttpRequestWrapper(
            method = "POST",
            path = "/chat",
            params = "message=hello",
            sourceNodeId = "test-node-123"
        )

        val jsonString = originalWrapper.toJson()

        val deserializedWrapper = HttpRequestWrapper.fromJson(jsonString)

        assertEquals(originalWrapper, deserializedWrapper)
    }
}
