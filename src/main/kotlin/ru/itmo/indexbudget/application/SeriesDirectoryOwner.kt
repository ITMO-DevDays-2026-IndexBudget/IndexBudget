package ru.itmo.indexbudget.application

import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/** Durable claim: cooperating series never remove or replace another run's ownership marker. */
internal class SeriesDirectoryOwner private constructor(val directory: Path, private val seriesId: String) {
    fun verify() {
        try {
            if (Files.readString(directory.resolve(MARKER)) != seriesId) fail()
        } catch (_: Exception) { fail() }
    }

    companion object {
        const val MARKER = ".indexbudget-series-owner"

        /** The caller has validated the directory; CREATE_NEW arbitrates concurrent claimants. */
        fun claim(directory: Path, seriesId: String): SeriesDirectoryOwner {
            try {
                Files.createDirectories(directory)
                Files.writeString(directory.resolve(MARKER), seriesId, CREATE_NEW, WRITE)
                Files.newDirectoryStream(directory).use { entries ->
                    if (entries.any { it.fileName.toString() != MARKER }) fail()
                }
                return SeriesDirectoryOwner(directory, seriesId)
            } catch (_: Exception) { fail() }
        }

        private fun fail(): Nothing = throw AnalysisException(AnalysisError(ErrorCode.EXPORT_FAILED,
            "Cannot claim budget series output directory; existing artifacts were preserved."))
    }
}
