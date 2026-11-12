package ru.itmo.indexbudget.domain

/** Explicit automatic-search methods; each method shares the same candidate and search budgets. */
enum class IndexMethod(val wireName: String) {
    BTREE("btree"), BRIN("brin");
}

fun CatalogColumn.operatorClassFor(method: String): String? =
    if (method == "btree") defaultOperatorClass else defaultOperatorClasses[method]

fun IndexDefinition.hasSupportedShape(): Boolean = !unique &&
    keys.map { it.column }.distinct().size == keys.size && when (method) {
        "btree" -> keys.size in 1..2
        "brin" -> keys.size == 1 && includedColumns.isEmpty() &&
            keys.all { it.direction == SortDirection.ASC && it.nulls == NullsOrder.LAST }
        else -> false
    }

/** Older report readers only understand B-tree definitions. Explicit methods require report v4. */
fun WorkloadInput.requiresMethodAwareReport(): Boolean =
    indexMethods.any { it != IndexMethod.BTREE } || manualIndexes.orEmpty().any { it.method != "btree" }

fun AnalysisReport.validateMethodFormat() {
    require(schemaVersion in 3..4 && specVersion == "0.2") { "Expected report v3/v4 / spec 0.2" }
    val hasOtherMethods = indexMethods.any { it != IndexMethod.BTREE } || input?.requiresMethodAwareReport() == true ||
        (candidates.candidates + manualComparison?.candidates.orEmpty()).any { it.definition.method != "btree" }
    require(!hasOtherMethods || schemaVersion == 4) { "BRIN requires report v4; do not reinterpret it as B-tree" }
}
