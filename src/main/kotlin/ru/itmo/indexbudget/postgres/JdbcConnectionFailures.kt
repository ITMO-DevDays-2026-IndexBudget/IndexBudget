package ru.itmo.indexbudget.postgres

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException
import java.util.Collections
import java.util.IdentityHashMap

/** Inspect driver chains without retaining messages or following cyclic/unbounded exception graphs. */
internal fun jdbcConnectionFailure(error: Throwable): Throwable? {
    val pending = ArrayDeque<Throwable>()
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    fun enqueue(next: Throwable?) {
        if (next != null && visited.size < 32 && visited.add(next)) pending.addLast(next)
    }
    enqueue(error)
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (current is SQLNonTransientConnectionException || current is SQLTransientConnectionException ||
            current is SQLRecoverableException || current is SocketException ||
            current is SocketTimeoutException || current is EOFException) return current
        if (current is SQLException) {
            val state = current.sqlState
            if (state?.startsWith("08") == true || state in setOf("57P01", "57P02", "57P03")) return current
            enqueue(current.nextException)
        }
        enqueue(current.cause)
        for (suppressed in current.suppressed) {
            if (visited.size >= 32) break
            enqueue(suppressed)
        }
    }
    return null
}
