package info.benjaminhill.localmesh.ui

import info.benjaminhill.localmesh.mesh.BridgeState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A global object that holds the application's UI state.
 *
 * ## What it does
 * - Acts as a simple, centralized "bulletin board" for sharing state between the `BridgeService`
 *   and the UI (`MainActivity` / `MainScreen`).
 * - Exposes `MutableStateFlow` objects for the service's state, status text, server URL, and logs,
 *   allowing the UI to reactively update when these values change.
 *
 * ## What it doesn't do
 * - It is not used for P2P communication. It is strictly for intra-app UI state management.
 * - It is not a persistent store. The state is reset when the application process is killed.
 */
object AppStateHolder {
    val currentState = MutableStateFlow<BridgeState>(BridgeState.Idle)
    val statusText = MutableStateFlow("Inactive")
    val serverUrl = MutableStateFlow<String?>(null)
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: MutableStateFlow<List<String>> = _logs

    fun addLog(message: String) {
        _logs.value = _logs.value + message
    }
}
