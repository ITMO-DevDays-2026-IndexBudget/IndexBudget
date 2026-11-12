package ru.itmo.indexbudget.sql

import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.PlainSelect
import ru.itmo.indexbudget.domain.StatementKind

/** Internal binding view only. It is never rendered or executed instead of the original DML. */
internal data class ValidatedSql(
    val statement: Statement,
    val scope: PlainSelect,
    val tables: List<Table>,
    val kind: StatementKind = StatementKind.SELECT,
    val assignments: List<Pair<Column, Expression>> = emptyList(),
)
