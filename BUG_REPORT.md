# Bug Report

This document outlines potential bugs, code smells, and other issues found during a review of the LocalMesh codebase.

---

## 1. ConcurrentModificationException in `TopologyOptimizer`

*   **Severity:** High
*   **File:** `mesh-logic/src/main/kotlin/info/benjaminhill/localmesh/logic/TopologyOptimizer.kt`
*   **Description:**
    The `cleanupNodeHopCounts` function iterates over `nodeHopCounts` and removes entries while the collection is potentially being modified by `listenForIncomingPayloads`. This can lead to a `ConcurrentModificationException` and crash the service. The `ConcurrentHashMap` is thread-safe for single operations, but not for concurrent iteration and modification.
*   **Code Snippet:**
    ```kotlin
    // In listenForIncomingPayloads()
    nodeHopCounts[it.sourceNodeId] = Pair(networkMessage.hopCount.toInt(), System.currentTimeMillis())

    // In cleanupNodeHopCounts()
    nodeHopCounts.entries.removeIf { (_, pair) ->
        now - pair.second > NODE_HOP_COUNT_EXPIRY_MS
    }
    ```

---

## 2. Unsafe UI Manipulation in `main.js`

*   **Severity:** High
*   **File:** `app/src/main/assets/web/main.js`
*   **Description:**
    The `displayFolder` function uses an `onclick` attribute with an unescaped folder name. If a folder were to have a name containing malicious HTML (e.g., `<img src=x onerror=alert(1)>`), it could lead to a cross-site scripting (XSS) vulnerability.
*   **Code Snippet:**
    ```javascript
    foldersList.innerHTML = contentFolders.map(folder => `<li onclick="displayFolder('${folder}')">${folder}</li>`).join('');
    ```

---

## 3. Potential Race Condition in `ServiceHardener`

*   **Severity:** Medium
*   **File:** `app/src/main/java/info/benjaminhill/localmesh/mesh/ServiceHardener.kt`
*   **Description:**
    The `scheduler` is initialized and started in `start()`, but it's not guaranteed to be stopped before `start()` is called again. The check `if (::scheduler.isInitialized && !scheduler.isShutdown)` is not sufficient to prevent a race condition where `start()` is called multiple times in quick succession, leading to multiple schedulers being created.
*   **Code Snippet:**
    ```kotlin
    fun start() {
        // ...
        if (::scheduler.isInitialized && !scheduler.isShutdown) {
            logger.log("Hardener scheduler already running.")
            return
        }
        scheduler = Executors.newSingleThreadScheduledExecutor()
        // ...
    }
    ```

---

## 5. `HttpClient` not closed in `ServiceHardener`

*   **Severity:** Low
*   **File:** `app/src/main/java/info/benjaminhill/localmesh/mesh/ServiceHardener.kt`
*   **Description:**
    The `HttpClient` in `ServiceHardener` is not closed in the `stop()` method. While the `ServiceHardener`'s lifecycle is tied to the service, this is a resource leak and not a good practice.
*   **Code Snippet:**
    ```kotlin
    class ServiceHardener(...) {
        private val client = HttpClient(CIO)
        // ...
        fun stop() {
            // ...
            // client.close() is missing
        }
    }
    ```

---

## 6. `NoCachePlugin` is not installed

*   **Severity:** Low
*   **File:** `app/src/main/java/info/benjaminhill/localmesh/LocalHttpServer.kt`
*   **Description:**
    The `NoCachePlugin` is defined but never installed in the Ktor server. This means that the server is not sending the "no-cache" headers, and browsers may be caching the UI, leading to stale content.
*   **Code Snippet:**
    ```kotlin
    val NoCachePlugin = createApplicationPlugin(name = "NoCachePlugin") { ... }

    class LocalHttpServer(...) {
        // ...
        fun start(): Boolean = logger.runCatchingWithLogging {
            server = embeddedServer(KtorCIO, port = PORT) {
                // install(NoCachePlugin) is missing
                // ...
            }
        }
    }
    ```