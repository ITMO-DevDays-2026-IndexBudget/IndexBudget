package ru.itmo.indexbudget.statistics

/** Diagnostics only, not a rate calculator: #107 owns conversion to a write profile. */
fun compareTableSnapshots(snapshots: List<TableSnapshot>): List<Map<String, Any>> {
    if (snapshots.size != 2) return emptyList()
    val (first, second) = snapshots
    val findings = mutableListOf<Map<String, Any>>()
    fun add(code: String, oid: Long? = null) {
        findings += if (oid == null) mapOf("code" to code) else mapOf("code" to code, "relation_oid" to oid)
    }
    if (first.source != second.source) { add("SOURCE_OR_CONDITIONS_CHANGED"); return findings }
    if (first.resetBefore != second.resetBefore || first.resetAfter != second.resetAfter ||
        first.postmasterStartedAt != second.postmasterStartedAt) add("RESET_METADATA_CHANGED")
    if (!first.complete || !second.complete) { add("INCOMPLETE_SNAPSHOTS"); return findings }
    val before = first.entries.associateBy { it.relationOid }
    val after = second.entries.associateBy { it.relationOid }
    for (oid in (before.keys - after.keys).sorted()) add("RELATION_DISAPPEARED", oid)
    for (oid in (after.keys - before.keys).sorted()) add("RELATION_APPEARED", oid)
    for (oid in (before.keys intersect after.keys).sorted()) {
        val a = before.getValue(oid); val b = after.getValue(oid)
        if (a.schema != b.schema || a.name != b.name) add("RELATION_RENAMED", oid)
        val old = listOf(a.nTupIns, a.nTupUpd, a.nTupDel, a.nTupHotUpd)
        val new = listOf(b.nTupIns, b.nTupUpd, b.nTupDel, b.nTupHotUpd)
        if (old.zip(new).any { (x, y) -> x != null && y != null && y < x }) add("COUNTERS_DECREASED", oid)
    }
    return findings
}
