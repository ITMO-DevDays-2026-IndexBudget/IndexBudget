package ru.itmo.indexbudget.report

import ru.itmo.indexbudget.domain.WorkloadInput
import ru.itmo.indexbudget.domain.WriteRatesOrigin
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Shared export/view of retained inputs. No rate, budget or metric calculation. */
internal object WriteProfileReport {
    fun origin(value: WriteRatesOrigin?) = value?.let { linkedMapOf(
        "kind" to "observed",
        "window" to linkedMapOf("from" to it.from, "to" to it.to, "period_seconds" to it.periodSeconds),
        "complete" to it.complete, "reasons" to it.reasons,
        "source" to linkedMapOf("source_id" to it.sourceId, "database_oid" to it.databaseOid,
            "postgres_version" to it.postgresVersion),
    ) }

    fun rates(input: WorkloadInput?) = input?.tableWriteRates?.toSortedMap()?.mapValues { (_, rate) -> linkedMapOf(
        "inserts_per_hour" to rate.insertsPerHour, "updates_per_hour" to rate.updatesPerHour,
        "deletes_per_hour" to rate.deletesPerHour, "source" to rate.source.name.lowercase(),
    ) }

    fun render(input: WorkloadInput?): String {
        val mapper = JsonMapper.builder().build()
        return render(mapper.valueToTree(rates(input)), mapper.valueToTree(origin(input?.tableWriteRatesOrigin)))
    }

    fun render(rates: JsonNode, origin: JsonNode): String = buildString {
        val observed = rates.isObject && rates.properties().any { it.value.path("source").isString && it.value.path("source").stringValue() == "observed" }
        append("<details class=\"write-profile\"><summary>Исходный профиль записи · строки/час · ")
            .append(when {
                observed -> "Наблюдаемый"
                rates.isObject && !rates.isEmpty -> "Ручной"
                else -> "Не задан"
            })
            .append("</summary>")
        append("<p>Профиль записи задаёт число изменённых строк в час отдельно от вызовов SQL. По нему рассчитывается write proxy — условная цена обслуживания индексов. Время и WAL эта модель не измеряет.</p>")
        if (!rates.isObject || rates.isEmpty) {
            append("<p>Профиль записи не задан. Неизвестные write units не заменяются нулём.</p>")
        } else {
            if (observed) appendObservation(origin)
            else append("<p>Ручной профиль записи. Окно наблюдения не задано.</p>")
            appendRates(rates)
        }
        append("</details>")
    }

    private fun StringBuilder.appendObservation(origin: JsonNode) {
        append("<p><strong>Наблюдаемый профиль</strong> · ")
        append(when {
            (origin.path("complete").isBoolean && origin.path("complete").booleanValue()) -> "Наблюдение полное"
            origin.path("complete").isBoolean -> "Наблюдение неполное"
            else -> "Полнота неизвестна"
        }).append("</p><dl class=\"values\">")
        for ((label, value) in listOf(
            "Начало окна" to origin.path("window").path("from"),
            "Конец окна" to origin.path("window").path("to"),
            "Длительность, с" to origin.path("window").path("period_seconds"),
            "Источник" to origin.path("source").path("source_id"),
            "БД OID" to origin.path("source").path("database_oid"),
            "PostgreSQL" to origin.path("source").path("postgres_version"),
        )) append("<div><dt>").append(label).append("</dt><dd>").append(h(display(value))).append("</dd></div>")
        append("</dl>")
        for (reason in origin.path("reasons")) append("<p class=\"warning-text\">").append(h(display(reason))).append("</p>")
        append("<p>В PG16 часть сбросов счётчиков отдельной таблицы нельзя обнаружить. HOT сохраняется для диагностики и не вычитается из модели затрат индекса.</p>")
        if (!(origin.path("complete").isBoolean && origin.path("complete").booleanValue())) {
            append("<p>Для включённого лимита нужен полный профиль. Собери совместимую пару снимков и повтори импорт или заполни ручной профиль. Пропущенные значения не считаются нулевыми.</p>")
        }
    }

    private fun StringBuilder.appendRates(rates: JsonNode) {
        append("<div class=\"table-scroll\" tabindex=\"0\" role=\"region\" aria-label=\"Профиль записи\"><table><thead><tr><th>Таблица</th><th>INSERT, строк/час</th><th>UPDATE, строк/час</th><th>DELETE, строк/час</th><th>Источник</th></tr></thead><tbody>")
        for ((name, rate) in rates.properties().sortedBy { it.key }) {
            append("<tr><th scope=\"row\">").append(h(name)).append("</th>")
            for (key in listOf("inserts_per_hour", "updates_per_hour", "deletes_per_hour", "source")) {
                append("<td>").append(h(display(rate.path(key)))).append("</td>")
            }
            append("</tr>")
        }
        append("</tbody></table></div>")
    }

    private fun display(value: JsonNode) = when {
        value.isNumber -> value.decimalValue().stripTrailingZeros().toPlainString().replace('.', ',')
        value.isString -> value.stringValue()
        else -> "Неизвестно"
    }

    private fun h(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
