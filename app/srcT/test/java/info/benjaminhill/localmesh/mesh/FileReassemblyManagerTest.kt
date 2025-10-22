package info.benjaminhill.localmesh.mesh

import android.content.Context
import info.benjaminhill.localmesh.logic.FileChunk
import info.benjaminhill.localmesh.util.AppLogger
import info.benjaminhill.localmesh.util.AssetManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class FileReassemblyManagerTest {

    private lateinit var manager: FileReassemblyManager
    private lateinit var mockContext: Context
    private lateinit var mockLogger: AppLogger

    @Before
    fun setUp() {
        mockContext = mock()
        mockLogger = mock()
        manager = FileReassemblyManager(mockContext, mockLogger)
        AssetManager.context = mockContext
    }

    @Test
    fun `addChunk assembles file when all chunks are received`() = runTest {
        // Given
        val fileId = "test-file"
        val destinationPath = "test.txt"
        val chunk1 = FileChunk(fileId, destinationPath, 0, 2, byteArrayOf(1, 2))
        val chunk2 = FileChunk(fileId, destinationPath, 1, 2, byteArrayOf(3, 4))

        // When
        manager.addChunk(chunk1)
        manager.addChunk(chunk2)

        // Then
        verify(mockLogger).log("Reassembling file '$destinationPath' ($fileId)")
        verify(mockLogger).log("Successfully reassembled and saved '$destinationPath'")
    }
}
