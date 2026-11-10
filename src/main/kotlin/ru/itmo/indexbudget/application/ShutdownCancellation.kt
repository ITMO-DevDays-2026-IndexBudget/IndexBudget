package ru.itmo.indexbudget.application

/** Registration is scoped to one invocation; the callback may run concurrently with its owner. */
fun interface ShutdownCancellation {
    fun register(cancel: () -> Unit): AutoCloseable
}

class JvmShutdownCancellation : ShutdownCancellation {
    override fun register(cancel: () -> Unit): AutoCloseable {
        val hook = Thread(cancel, "indexbudget-cancel")
        Runtime.getRuntime().addShutdownHook(hook)
        return AutoCloseable {
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (_: IllegalStateException) {
                // JVM shutdown has already started; the cancellation callback owns this hook.
            }
        }
    }
}
