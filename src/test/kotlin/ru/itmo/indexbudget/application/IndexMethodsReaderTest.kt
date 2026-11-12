package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.domain.*
import java.nio.file.Files
import java.nio.file.Path

class IndexMethodsReaderTest {
    @TempDir lateinit var directory: Path
    private fun read(extra: String = ""): WorkloadInput {
        val path = directory.resolve("workload.json")
        Files.writeString(path, """{"schema_version":2,"queries":[{"id":"q","sql":"SELECT a FROM public.t WHERE a > 1"}],
            "profiles":[{"id":"p","weight":1,"calls_per_hour":{"q":1}}],
            "constraints":{"storage_budget_bytes":1000000,"write_budget_units_per_hour":null}$extra} """)
        return WorkloadReader().read(path)
    }

    @Test
    fun `old inputs retain B-tree and methods must be explicitly valid`() {
        assertEquals(setOf(IndexMethod.BTREE), read().indexMethods)
        assertEquals(setOf(IndexMethod.BRIN), read(",\"index_methods\":[\"brin\"]").indexMethods)
        assertEquals(IndexMethod.entries.toSet(), read(",\"index_methods\":[\"brin\",\"btree\"]").indexMethods)
        for (value in listOf("null", "[]", "{}", "42", "[\"hash\"]", "[\"BRIN\"]", "[\"brin\",\"brin\"]")) {
            assertThrows(AnalysisException::class.java, { read(",\"index_methods\":$value") }, value)
        }
    }

    @Test
    fun `manual method is independent from automatic selection but BRIN cannot accept sort or INCLUDE`() {
        val manual = """, "manual_indexes":[{"schema":"public","table":"t","method":"brin","keys":[{"column":"a"}]}]"""
        assertEquals("brin", read(manual).manualIndexes!!.single().method)
        assertEquals("btree", read(manual.replace("\"method\":\"brin\",", "")).manualIndexes!!.single().method)
        for (invalid in listOf(manual.replace("\"column\":\"a\"", "\"column\":\"a\",\"direction\":\"asc\""),
            manual.replace("\"column\":\"a\"", "\"column\":\"a\",\"nulls\":\"last\""),
            manual.replace("\"keys\":", "\"include\":[\"b\"],\"keys\":"),
            manual.replace("[{\"column\":\"a\"}]", "[{\"column\":\"a\"},{\"column\":\"b\"}]"),
            manual.replace("\"brin\"", "\"hash\""))) {
            assertThrows(AnalysisException::class.java) { read(invalid) }
        }
    }
}
