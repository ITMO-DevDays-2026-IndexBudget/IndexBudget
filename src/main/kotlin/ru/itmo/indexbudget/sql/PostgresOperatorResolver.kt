package ru.itmo.indexbudget.sql

import ru.itmo.indexbudget.domain.*

/** PostgreSQL 16 typeconv-oper rules for the supported binary comparisons. */
internal class PostgresOperatorResolver(private val catalog: CatalogSnapshot, private val context: RunContext) {
    private fun facts() = catalog.semantics ?: fail(ErrorCode.CATALOG_ERROR, "Catalog is missing PostgreSQL operator facts.")
    private val types by lazy { facts().types.associate { context.checkDeadline(); it.oid to it } }
    private val casts by lazy { facts().implicitCasts.mapTo(hashSetOf()) {
        context.checkDeadline(); it.sourceTypeOid to it.targetTypeOid
    } }
    private val operators by lazy { facts().operators.groupBy { context.checkDeadline(); it.name } }

    fun requireBuiltin(name: String, left: CatalogType?, right: CatalogType?) {
        context.checkDeadline()
        val inputs = listOf(left, right)
        // Visibility, including signature shadowing and exclusion of pg_temp, was determined by the server.
        var candidates = operators[name].orEmpty()
        val exactLeft = left ?: right
        val exactRight = right ?: left
        val exact = candidates.singleOrNull {
            context.checkDeadline()
            it.leftTypeOid == exactLeft?.oid && it.rightTypeOid == exactRight?.oid
        }
        if (exact != null) return accept(exact)

        fun arguments(op: CatalogOperator) = listOf(op.leftTypeOid, op.rightTypeOid)
        fun canCoerce(input: CatalogType?, target: Long): Boolean {
            if (input == null || input.oid == target) return true
            // PostgreSQL find_coercion_pathway unwraps domains before consulting pg_cast.
            // Keep the declared operator types unchanged for exact-match ranking below.
            val sourceType = baseType(input)
            val targetType = baseType(types[target]
                ?: fail(ErrorCode.CATALOG_ERROR, "Catalog is missing an operator argument type."))
            if (sourceType.oid == targetType.oid || sourceType.oid to targetType.oid in casts) return true
            return when (targetType.name) {
                "anyarray", "anycompatiblearray" -> sourceType.category == "A"
                "anyrange", "anycompatiblerange" -> sourceType.kind == "r"
                "anymultirange", "anycompatiblemultirange" -> sourceType.kind == "m"
                "anyenum" -> sourceType.kind == "e"
                else -> false
            }
        }
        candidates = candidates.filter { op ->
            context.checkDeadline()
            arguments(op).zip(inputs).all { (target, input) -> canCoerce(input, target) }
        }
        fun bestBy(score: (CatalogOperator) -> Int) {
            val best = candidates.maxOfOrNull { context.checkDeadline(); score(it) } ?: return
            candidates = candidates.filter { context.checkDeadline(); score(it) == best }
        }
        if (candidates.size > 1) bestBy { op -> arguments(op).zip(inputs).count { (target, input) -> target == input?.oid } }
        if (candidates.size > 1) bestBy { op -> arguments(op).zip(inputs).count { (target, input) ->
            input != null && target != input.oid && types[target]?.let { it.preferred && it.category == input.category } == true
        } }
        if (candidates.size > 1) {
            for (position in inputs.indices.filter { inputs[it] == null }) {
                val accepted = candidates.mapNotNull { context.checkDeadline(); types[arguments(it)[position]] }
                val categories = accepted.map { it.category }.toSet()
                val category = if ("S" in categories) "S" else categories.singleOrNull()
                    ?: fail(ErrorCode.UNSUPPORTED_SQL, "Ambiguous PostgreSQL operator argument category.")
                val preferred = accepted.any { it.category == category && it.preferred }
                candidates = candidates.filter { op -> context.checkDeadline(); types[arguments(op)[position]]?.let {
                    it.category == category && (!preferred || it.preferred)
                } == true }
            }
        }
        if (candidates.size > 1 && inputs.any { it == null }) {
            val known = inputs.filterNotNull().distinctBy { it.oid }.singleOrNull()
            if (known != null) {
                val inferred = candidates.filter { op -> arguments(op).all { canCoerce(known, it) } }
                if (inferred.size == 1) candidates = inferred
            }
        }
        val chosen = candidates.singleOrNull()
            ?: fail(ErrorCode.UNSUPPORTED_SQL, "Comparison operator is missing or ambiguous in PostgreSQL.")
        accept(chosen)
    }

    private fun baseType(type: CatalogType): CatalogType {
        var current = type
        val visited = hashSetOf<Long>()
        while (current.kind == "d") {
            context.checkDeadline()
            if (!visited.add(current.oid)) fail(ErrorCode.CATALOG_ERROR, "Catalog contains a cyclic domain type chain.")
            current = types[current.baseTypeOid]
                ?: fail(ErrorCode.CATALOG_ERROR, "Catalog is missing a domain base type.")
        }
        return current
    }

    private fun accept(operator: CatalogOperator) {
        context.checkDeadline()
        if (!operator.isBuiltin) fail(ErrorCode.UNSUPPORTED_SQL, "User-defined comparison operators are not supported.")
    }

    private fun fail(code: ErrorCode, message: String): Nothing = throw AnalysisException(AnalysisError(code, message))
}
