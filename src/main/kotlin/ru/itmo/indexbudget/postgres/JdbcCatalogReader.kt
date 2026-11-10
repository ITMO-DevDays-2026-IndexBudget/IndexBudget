package ru.itmo.indexbudget.postgres

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.AnalysisWarning
import ru.itmo.indexbudget.domain.CatalogColumn
import ru.itmo.indexbudget.domain.CatalogSnapshot
import ru.itmo.indexbudget.domain.CatalogTable
import ru.itmo.indexbudget.domain.ColumnRef
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.ExistingIndex
import ru.itmo.indexbudget.domain.IndexKey
import ru.itmo.indexbudget.domain.NullsOrder
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.SortDirection
import ru.itmo.indexbudget.domain.TableKind
import ru.itmo.indexbudget.domain.TableName
import ru.itmo.indexbudget.domain.TableRef
import ru.itmo.indexbudget.domain.WarningCode

/**
 * Reads only requested relations on the caller's physical connection. Does not own/close it,
 * commit, ANALYZE, change search_path or create indexes. AnalysisSession delegates here in #13.
 * TableName values are parsed identifiers (already folded by the SQL parser if unquoted).
 */
class JdbcCatalogReader(private val connection: Connection) {
    fun readCatalog(tables: Set<TableName>, context: RunContext): CatalogSnapshot {
        context.checkDeadline()
        try {
            val path = query("SELECT unnest(pg_catalog.current_schemas(true))", context) { getString(1) }
            val found = linkedMapOf<TableRef, CatalogTable>()
            val unresolved = mutableListOf<TableName>()
            for (name in tables.sortedWith(compareBy({ it.schema ?: "" }, { it.name }))) {
                val qualified = listOfNotNull(name.schema, name.name).joinToString(".", transform = ::quoteIdentifier)
                val relation = query(RELATION, context, qualified) {
                    Relation(getLong("oid"), TableRef(getString("schema_name"), getString("relname")),
                        getString("relkind"), getDouble("reltuples").takeIf { it >= 0 },
                        getBoolean("statistics_available"), getBoolean("readable"))
                }.singleOrNull()
                if (relation == null) {
                    unresolved += name
                    continue
                }
                if (!relation.readable) fail("Workload relation is not readable")
                if (relation.ref in found) continue
                val columns = query(COLUMNS, context, relation.oid) {
                    CatalogColumn(
                        name = getString("attname"), typeName = getString("type_name"),
                        nullable = !getBoolean("attnotnull"), collation = getString("collation"),
                        defaultOperatorClass = getString("opclass"), isBuiltinType = getBoolean("builtin"),
                    )
                }
                val indexes = readIndexes(relation, context)
                found[relation.ref] = CatalogTable(relation.ref, columns, relation.rows, relation.statistics,
                    indexes, when (relation.kind) {
                        "r" -> TableKind.ORDINARY
                        "v", "m" -> TableKind.VIEW
                        "p" -> TableKind.PARTITIONED
                        "f" -> TableKind.FOREIGN
                        else -> TableKind.OTHER
                    })
            }
            val ordered = found.values.sortedWith(compareBy({ it.ref.schema }, { it.ref.name }))
            context.checkDeadline()
            return CatalogSnapshot(ordered, path, unresolved, ordered.filterNot { it.statisticsAvailable }.map {
                AnalysisWarning(WarningCode.MISSING_STATISTICS, "Planner statistics are unavailable; estimates may be inaccurate",
                    details = mapOf("schema" to it.ref.schema, "table" to it.ref.name))
            })
        } catch (error: SQLException) {
            context.checkDeadline()
            // JDBC messages/causes can contain connection details and SQL. Do not publish them.
            fail("Unable to read PostgreSQL catalog", error.sqlState)
        }
    }

    private fun readIndexes(relation: Relation, context: RunContext): List<ExistingIndex> {
        val rows = query(INDEXES, context, relation.oid) {
            IndexRow(getLong("indexrelid"), getString("index_name"), getString("method"),
                getBoolean("indisvalid"), getBoolean("indisready"),
                getBoolean("indislive") && !getBoolean("indcheckxmin"), getBoolean("indisunique"),
                getString("predicate"), getInt("position") > getInt("indnkeyatts"),
                getString("attname"), getString("expression"), getInt("options"),
                getString("collation"), getString("opclass"))
        }
        return rows.groupBy { it.oid }.values.map { parts ->
            val index = parts.first()
            ExistingIndex(
                name = index.name, table = relation.ref, method = index.method,
                keys = parts.filter { !it.included && it.column != null }.map {
                    IndexKey(ColumnRef(relation.ref, requireNotNull(it.column)),
                        if (index.method == "btree" && it.options and 1 != 0) SortDirection.DESC else SortDirection.ASC,
                        if (index.method == "btree" && it.options and 2 != 0) NullsOrder.FIRST else NullsOrder.LAST,
                        it.collation, it.opclass)
                },
                valid = index.valid, ready = index.ready, accessible = index.accessible,
                unique = index.unique, predicate = index.predicate,
                expressions = parts.mapNotNull { it.expression },
                includedColumns = parts.filter { it.included }.mapNotNull { it.column?.let { name -> ColumnRef(relation.ref, name) } },
            )
        }
    }

    private fun <T> query(sql: String, context: RunContext, parameter: Any? = null, row: ResultSet.() -> T): List<T> {
        context.checkDeadline()
        return connection.prepareStatement(sql).use { statement ->
            // JDBC uses whole seconds; round upwards, then check the shared deadline after execution
            // and on each row. No EXPLAIN attempts are consumed by catalog reads.
            statement.queryTimeout = ((context.remainingTime().toMillis() + 999) / 1000)
                .coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            if (parameter != null) statement.setObject(1, parameter)
            statement.executeQuery().use { result ->
                val values = mutableListOf<T>()
                while (result.next()) {
                    context.checkDeadline()
                    values += result.row()
                }
                context.checkDeadline()
                values
            }
        }
    }

    private fun fail(message: String, state: String? = null): Nothing = throw AnalysisException(
        AnalysisError(ErrorCode.CATALOG_ERROR, message,
            details = state?.takeIf { it.matches(Regex("[A-Z0-9]{5}")) }?.let { mapOf("sql_state" to it) } ?: emptyMap()),
    )

    private data class Relation(val oid: Long, val ref: TableRef, val kind: String, val rows: Double?,
        val statistics: Boolean, val readable: Boolean)
    private data class IndexRow(val oid: Long, val name: String, val method: String, val valid: Boolean,
        val ready: Boolean, val accessible: Boolean, val unique: Boolean, val predicate: String?,
        val included: Boolean, val column: String?, val expression: String?, val options: Int,
        val collation: String?, val opclass: String?)

    private companion object {
        fun quoteIdentifier(value: String) = "\"" + value.replace("\"", "\"\"") + "\""

        val RELATION = """
            SELECT c.oid, n.nspname AS schema_name, c.relname, c.relkind, c.reltuples,
                   pg_catalog.has_schema_privilege(n.oid, 'USAGE') AND
                   pg_catalog.has_table_privilege(c.oid, 'SELECT') AS readable,
                   EXISTS (SELECT 1 FROM pg_catalog.pg_stats s
                           WHERE s.schemaname = n.nspname AND s.tablename = c.relname) AS statistics_available
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE c.oid = pg_catalog.to_regclass(?)
        """.trimIndent()

        val COLUMNS = """
            SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod) AS type_name, a.attnotnull,
                   tn.nspname = 'pg_catalog' AS builtin,
                   CASE WHEN co.oid IS NOT NULL THEN pg_catalog.format('%I.%I', cn.nspname, co.collname) END AS collation,
                   defaults.opclass
            FROM pg_catalog.pg_attribute a
            JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
            JOIN pg_catalog.pg_namespace tn ON tn.oid = t.typnamespace
            LEFT JOIN pg_catalog.pg_collation co ON co.oid = a.attcollation
            LEFT JOIN pg_catalog.pg_namespace cn ON cn.oid = co.collnamespace
            LEFT JOIN LATERAL (
                SELECT pg_catalog.format('%I.%I', ns.nspname, op.opcname) AS opclass
                FROM pg_catalog.pg_opclass op
                JOIN pg_catalog.pg_am am ON am.oid = op.opcmethod AND am.amname = 'btree'
                JOIN pg_catalog.pg_namespace ns ON ns.oid = op.opcnamespace
                WHERE op.opcdefault AND op.opcintype = CASE
                    WHEN t.oid = 'pg_catalog.varchar'::pg_catalog.regtype THEN 'pg_catalog.text'::pg_catalog.regtype
                    WHEN t.typelem <> 0 AND t.typsubscript = 'pg_catalog.array_subscript_handler'::pg_catalog.regproc
                        THEN 'pg_catalog.anyarray'::pg_catalog.regtype
                    WHEN t.typtype = 'r' THEN 'pg_catalog.anyrange'::pg_catalog.regtype
                    WHEN t.typtype = 'm' THEN 'pg_catalog.anymultirange'::pg_catalog.regtype
                    ELSE t.oid END
            ) defaults ON true
            WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
        """.trimIndent()

        val INDEXES = """
            SELECT i.indexrelid, ic.relname AS index_name, am.amname AS method,
                   i.indisvalid, i.indisready, i.indislive, i.indcheckxmin, i.indisunique,
                   pg_catalog.pg_get_expr(i.indpred, i.indrelid) AS predicate,
                   k.position, i.indnkeyatts, a.attname, i.indoption[(k.position - 1)::integer] AS options,
                   CASE WHEN k.attnum = 0 THEN pg_catalog.pg_get_indexdef(i.indexrelid, k.position::integer, false) END AS expression,
                   CASE WHEN co.oid IS NOT NULL THEN pg_catalog.format('%I.%I', cn.nspname, co.collname) END AS collation,
                   CASE WHEN op.oid IS NOT NULL THEN pg_catalog.format('%I.%I', ons.nspname, op.opcname) END AS opclass
            FROM pg_catalog.pg_index i
            JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
            JOIN pg_catalog.pg_am am ON am.oid = ic.relam
            CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, position)
            LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
            LEFT JOIN pg_catalog.pg_collation co ON co.oid = i.indcollation[(k.position - 1)::integer]
            LEFT JOIN pg_catalog.pg_namespace cn ON cn.oid = co.collnamespace
            LEFT JOIN pg_catalog.pg_opclass op ON op.oid = i.indclass[(k.position - 1)::integer]
            LEFT JOIN pg_catalog.pg_namespace ons ON ons.oid = op.opcnamespace
            WHERE i.indrelid = ?
            ORDER BY ic.relname, i.indexrelid, k.position
        """.trimIndent()
    }
}
