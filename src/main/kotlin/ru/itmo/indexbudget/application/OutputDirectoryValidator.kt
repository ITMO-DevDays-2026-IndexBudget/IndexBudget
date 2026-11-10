package ru.itmo.indexbudget.application

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Проверяет назначение до анализа; создание каталога и повторная проверка остаются экспорту. */
@Component
class OutputDirectoryValidator {
    fun validate(path: Path) {
        try {
            val output = path.toAbsolutePath()
            var existing = output.root
            for ((index, part) in output.withIndex()) {
                val next = existing.resolve(part)
                val attributes = try {
                    Files.readAttributes(next, BasicFileAttributes::class.java)
                } catch (_: NoSuchFileException) {
                    // Висячая ссылка не является свободным путём для нового каталога.
                    if (Files.exists(next, NOFOLLOW_LINKS)) {
                        invalid("Output directory must not be a dangling symbolic link.")
                    }
                    // Не нормализуем весь путь: это изменило бы смысл '..' после существующей ссылки.
                    // При создании отсутствующего родителя '..' может привести в прежний каталог.
                    if (output.drop(index + 1).any { it.toString() == ".." }) {
                        invalid("Output path must not traverse '..' after a missing directory.")
                    }
                    checkWritable(existing)
                    return
                }
                if (!attributes.isDirectory) {
                    invalid("Output path and its existing parents must be directories.")
                }
                existing = next
            }
            Files.newDirectoryStream(output).use { entries ->
                if (entries.iterator().hasNext()) {
                    reject(ErrorCode.OUTPUT_DIRECTORY_NOT_EMPTY,
                        "Output directory must be empty; use a separate directory for each run.")
                }
            }
            checkWritable(output)
        } catch (_: IOException) {
            reject(ErrorCode.EXPORT_FAILED, "Cannot inspect the output directory.")
        } catch (_: DirectoryIteratorException) {
            reject(ErrorCode.EXPORT_FAILED, "Cannot list the output directory.")
        } catch (_: SecurityException) {
            reject(ErrorCode.EXPORT_FAILED, "Cannot access the output directory.")
        }
    }

    private fun checkWritable(path: Path) {
        if (!Files.isWritable(path) || !Files.isExecutable(path)) {
            reject(ErrorCode.EXPORT_FAILED, "Output directory is not writable or accessible.")
        }
    }

    private fun invalid(message: String): Nothing = reject(ErrorCode.INVALID_INPUT, message)

    private fun reject(code: ErrorCode, message: String): Nothing {
        throw AnalysisException(AnalysisError(code, message, details = mapOf("field" to "output_dir")))
    }
}
