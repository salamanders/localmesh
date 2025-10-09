package info.benjaminhill.localmesh.util

/**
 * A centralized place for handling exceptions throughout the application.
 */
object GlobalExceptionHandler {

    /**
     * Executes a block of code and catches any exceptions, logging them to the provided logger.
     * This is a simple way to replace repetitive try-catch blocks for non-critical operations.
     *
     * @param log A lambda that takes a message and an optional Throwable, used for logging.
     * @param block The block of code to execute.
     */
    inline fun <T> runCatchingWithLogging(
        crossinline log: (String, Throwable?) -> Unit,
        block: () -> T
    ): T? {
        return try {
            block()
        } catch (e: Throwable) {
            log("Exception caught: ${e.message}", e)
            null
        }
    }
}