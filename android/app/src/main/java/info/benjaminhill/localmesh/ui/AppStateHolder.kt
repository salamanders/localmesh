package info.benjaminhill.localmesh.ui

import info.benjaminhill.localmesh.mesh.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A simple global object to hold and share the service's state with the UI.
 * This acts as a "bulletin board" for different parts of the app to communicate through.
 */
object AppStateHolder {
    val currentState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val statusText = MutableStateFlow("Inactive")
    val serverUrl = MutableStateFlow<String?>(null)
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: MutableStateFlow<List<String>> = _logs

    fun addLog(message: String) {
        _logs.value = _logs.value + message
    }
}
