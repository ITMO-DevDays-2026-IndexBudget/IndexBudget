package ru.itmo.indexbudget.sql

import ru.itmo.indexbudget.domain.*

/** PostgreSQL 16 typeconv-oper rules for the supported binary comparisons. */
internal class PostgresOperatorResolver(private val catalog: CatalogSnapshot, private val context: RunContext) {
    private fun facts() = catalog.semantics ?: fail(ErrorCode.CATALOG_ERROR, "Catalog is missing PostgreSQL operator facts.")
    private val types by lazy { facts().types.associate { context.checkDeadline(); it.oid to it } }
    private val casts by lazy { facts().implicitCasts.filterNot { it.assignmentOnly }.mapTo(hashSetOf()) {
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

    /** PG16 transformAExprIn: multi-literal lists prefer a common scalar-array type. */
    fun requireBuiltinIn(left: CatalogType, values: List<CatalogType?>) {
        context.checkDeadline()
        val common = if (values.size > 1) commonType(listOf(left) + values) else null
        if (common != null && common.arrayTypeOid != 0L) {
            val array = types[common.arrayTypeOid]
                ?: fail(ErrorCode.CATALOG_ERROR, "Catalog is missing an IN array type.")
            if (array.elementTypeOid != common.oid) fail(ErrorCode.CATALOG_ERROR, "IN array element type does not match.")
            // PostgreSQL coerces RHS array elements, but resolves the operator with the original LHS type.
            requireBuiltin("=", left, common)
        } else {
            for (value in values) {
                context.checkDeadline()
                requireBuiltin("=", left, value)
            }
        }
    }

    private fun commonType(inputs: List<CatalogType?>): CatalogType? {
        val known = inputs.mapNotNull { context.checkDeadline(); it?.let(::baseType) }
        var candidate = known.firstOrNull() ?: return null
        for (type in known.drop(1)) {
            context.checkDeadline()
            if (candidate.category != type.category) return null
            if (!candidate.preferred && canCoerce(candidate, type.oid) && !canCoerce(type, candidate.oid)) candidate = type
        }
        return candidate.takeIf { inputs.all { context.checkDeadline(); canCoerce(it, candidate.oid) } }
    }

    private fun canCoerce(input: CatalogType?, target: Long): Boolean {
        context.checkDeadline()
        if (input == null || input.oid == target) return true
        // Domain unwrapping is shared with ordinary binary operator resolution.
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
