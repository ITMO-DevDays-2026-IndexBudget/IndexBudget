package ru.itmo.indexbudget.report

import ru.itmo.indexbudget.domain.AnalysisReport

interface ReportRenderer {
    /** Render autonomous HTML with escaped SQL/text. Use only the saved report; do not reevaluate sets. */
    fun render(report: AnalysisReport): String
}
