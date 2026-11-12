package ru.itmo.indexbudget.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

class KotlinSourceConventionsTest {
    @Test
    fun `Kotlin sources use explicit null contracts instead of unchecked assertions`() {
        val operator = "!".repeat(2)
        val violations = Files.walk(Path.of("src")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "kt" }
                .flatMap { path ->
                    path.readText().lineSequence().mapIndexedNotNull { index, line ->
                        if (operator in line) "$path:${index + 1}" else null
                    }.toList().stream()
                }
                .sorted()
                .toList()
        }
        assertTrue(violations.isEmpty(), "Use explicit null checks in Kotlin sources: ${violations.joinToString()}")
    }
}
