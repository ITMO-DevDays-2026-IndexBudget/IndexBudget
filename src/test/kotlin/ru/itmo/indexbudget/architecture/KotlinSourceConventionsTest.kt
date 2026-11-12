package ru.itmo.indexbudget.architecture

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KotlinSourceConventionsTest {
    private val disposable = Disposer.newDisposable("Kotlin source conventions")
    @OptIn(CoreEnvironmentDeprecation::class)
    private val psiFactory by lazy {
        val environment = KotlinCoreEnvironment.createForProduction(
            disposable, CompilerConfiguration.create(), EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
        KtPsiFactory(environment.project)
    }

    @AfterAll
    fun disposeParser() {
        Disposer.dispose(disposable)
    }

    @Test
    fun `Kotlin sources use explicit null contracts instead of unchecked assertions`() {
        val violations = Files.walk(Path.of("src")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "kt" }
                .flatMap { path ->
                    uncheckedAssertionLines(path.readText()).map { line -> "$path:$line" }.stream()
                }
                .sorted()
                .toList()
        }
        assertTrue(violations.isEmpty(), "Use explicit null checks in Kotlin sources: ${violations.joinToString()}")
    }

    @Test
    fun `unchecked assertion tokens are detected with their source lines`() {
        val source = "// !! is only text here\r\nval first = value!!\r\nval second = other!!.length"
        assertEquals(listOf(2, 3), uncheckedAssertionLines(source))
    }

    @Test
    fun `literal text comments and quoted identifiers are allowed`() {
        val quotes = "\"".repeat(3)
        val sources = listOf(
            "val sql = \"SELECT id FROM records WHERE payload = '!!'\"",
            "val text = \"Escaped quote: \\\" !!\"",
            "val raw = $quotes\n!!\n$quotes",
            "// !!\nval number = 1",
            "/* !! /* nested !! */ !! */ val number = 1",
            "val `!!` = 1",
            "val character = '!'",
            "val negation = !flag; val comparison = left != right",
            "val doubleNegation = !!flag",
        )
        sources.forEach { source -> assertEquals(emptyList<Int>(), uncheckedAssertionLines(source), source) }
    }

    @Test
    fun `assertions inside string template expressions are still detected`() {
        val quotes = "\"".repeat(3)
        val sources = listOf(
            "val text = \"\${value!!}\"",
            "val raw = $quotes\${value!!}$quotes",
            "val nested = \"\${\"\${value!!}\"}\"",
        )
        sources.forEach { source -> assertEquals(listOf(1), uncheckedAssertionLines(source), source) }
    }

    private fun uncheckedAssertionLines(source: String): List<Int> =
        PsiTreeUtil.collectElementsOfType(psiFactory.createFile(source), KtPostfixExpression::class.java)
            .filter { it.operationToken == KtTokens.EXCLEXCL }
            .map { expression -> source.take(expression.operationReference.textOffset).count { it == '\n' } + 1 }
            .sorted()
}
