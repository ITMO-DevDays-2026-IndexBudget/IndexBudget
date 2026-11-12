package ru.itmo.indexbudget.experiments

import ru.itmo.indexbudget.statistics.TableWriteSnapshotJson
import ru.itmo.indexbudget.statistics.WriteProfileCalculator
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/** Experiment bridge only: reuse #107, never duplicate its rate arithmetic in a load runner. */
fun main(args: Array<String>) {
    require(args.size == 4)
    val profile = WriteProfileCalculator().calculate(TableWriteSnapshotJson.read(Path.of(args[1])), TableWriteSnapshotJson.read(Path.of(args[2])))
    require(profile.complete)
    val mapper = JsonMapper.builder().build()
    @Suppress("UNCHECKED_CAST")
    val workload = mapper.readValue(Files.readString(Path.of(args[0])), Map::class.java).toMutableMap() as MutableMap<String, Any?>
    val tables = setOf("public.records", "public.groups") // exact tables of the immutable #106 scenario
    require(profile.rates.keys.containsAll(tables))
    workload["table_write_rates"] = tables.sorted().associateWith { name ->
        val r = profile.rates.getValue(name)
        mapOf("inserts_per_hour" to r.insertsPerHour, "updates_per_hour" to r.updatesPerHour,
            "deletes_per_hour" to r.deletesPerHour, "source" to "observed")
    }
    workload["table_write_rates_origin"] = mapOf("kind" to "observed", "complete" to true, "reasons" to emptyList<String>(),
        "window" to mapOf("from" to profile.from, "to" to profile.to, "period_seconds" to profile.periodSeconds),
        "source" to mapOf("source_id" to profile.sourceId.toString(), "database_oid" to profile.databaseOid, "postgres_version" to profile.postgresVersion))
    Files.writeString(Path.of(args[3]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(workload))
}
