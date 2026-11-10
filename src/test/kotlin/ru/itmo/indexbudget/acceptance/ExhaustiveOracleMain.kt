package ru.itmo.indexbudget.acceptance

import ru.itmo.indexbudget.application.JvmShutdownCancellation
import ru.itmo.indexbudget.application.ResolvedWorkloadValidator
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.postgres.JdbcAnalysisSessionFactory
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/** Separate test-runtime program. Its explicitly supplied deadline includes preparation and baseline. */
fun main(args: Array<String>) {
    val options = try { OracleOptions.parse(args) } catch (_: IllegalArgumentException) {
        System.err.println("Usage: exhaustiveOracle --workload PATH --output PATH --deadline-seconds N [--candidate-ids PATH]")
        exitProcess(2)
    }
    val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()
    val deadline = Duration.ofSeconds(options.deadlineSeconds)
    val context = RunContext(limits = RunLimits(commandTimeout = deadline, searchTimeout = deadline))
    var input: WorkloadInput? = null
    var candidates = emptyList<IndexCandidate>()
    var result = OracleResult(RunStatus.FAILED, 0, emptyList(), null, null, context.statistics(), emptyList(), false)
    val active = AtomicReference<AnalysisSession?>()
    val cancellation = JvmShutdownCancellation().register {
        context.cancel()
        try { active.get()?.cancel() } catch (_: Exception) { /* the main path also closes */ }
    }
    try {
        require(!Files.exists(options.output)) { "Output must be a new file" }
        input = WorkloadReader().read(options.workload, context)
        val analyzer = JSqlParserSqlAnalyzer()
        context.enterPhase(AnalysisPhase.INTROSPECTION)
        val parsed = analyzer.parse(input, context)
        val session = JdbcAnalysisSessionFactory().open(context)
        active.set(session)
        val catalog = session.readCatalog(parsed.referencedTables, context)
        context.recordDatabaseVersions(session.databaseVersions)
        val resolved = analyzer.resolve(parsed, catalog, context)
        ResolvedWorkloadValidator().validate(resolved, context)
        context.completePhase()
        val shortlist = ProfileAwareCandidateGenerator().generate(resolved, catalog, context).candidates
        candidates = if (options.candidateIds == null) {
            require(shortlist.size <= 8) { "Choose an explicit fixed space of at most eight candidates" }
            shortlist
        } else {
            val bytes = Files.newInputStream(options.candidateIds).use { it.readNBytes(1024 * 1024 + 1) }
            require(bytes.size <= 1024 * 1024) { "Candidate ID file is too large" }
            val ids = try {
                requireNotNull(mapper.readTree(bytes))
            } catch (_: JacksonException) {
                throw IllegalArgumentException("Invalid candidate ID JSON")
            }
            require(ids.isArray && ids.size() <= 8 && ids.all { it.isString }) { "Expected a JSON array of up to eight IDs" }
            val selected = ids.toList().map { it.stringValue() }
            require(selected.distinct().size == selected.size) { "Repeated candidate IDs" }
            val byId = shortlist.associateBy { it.id }
            require(selected.all { it in byId }) { "Every fixed candidate ID must exist in the generated shortlist" }
            selected.sorted().map { byId.getValue(it) }
        }
        val metrics = MetricsCalculatorImpl()
        candidates = candidates.sortedBy { it.id }.map { candidate ->
            context.checkDeadline()
            val sized = candidate.copy(estimatedBytes = session.estimateSize(candidate, context))
            val usage = metrics.assessBudget(listOf(sized), input).usage
            sized.copy(budgetedBytes = usage.budgetedBytes, writeUnitsPerHour = usage.writeUnitsPerHour)
        }
        result = ExhaustiveOracle(metrics).run(resolved, candidates, session, context)
    } catch (error: AnalysisException) {
        for (operationError in oracleOperationErrors(error)) result = result.withFailure(operationError, context)
    } catch (_: IllegalArgumentException) {
        result = result.withFailure(AnalysisError(ErrorCode.INVALID_INPUT, "Invalid oracle options or candidate space."), context)
    } catch (_: Exception) {
        result = result.withFailure(AnalysisError(ErrorCode.INTERNAL_ERROR, "Oracle preparation failed."), context)
    } finally {
        active.getAndSet(null)?.let { session ->
            try {
                if (context.isCancelled || context.remainingTime().isZero) session.cancel()
                session.close()
            } catch (_: Exception) {
                result = result.withFailure(AnalysisError(ErrorCode.DB_CONNECTION_ERROR, "Oracle session cleanup failed."), context)
            }
        }
        cancellation.close()
    }
    val document = linkedMapOf(
        "experiment" to "exhaustive_oracle",
        "format_version" to 1,
        "spec_version" to "0.2",
        "run_id" to context.runId,
        "input_hash" to context.inputHash,
        "status" to result.status.name.lowercase(),
        "deadline_seconds" to options.deadlineSeconds,
        "duration_millis" to context.elapsedTime().toMillis(),
        "environment" to linkedMapOf(
            "java_version" to System.getProperty("java.version"),
            "kotlin_version" to KotlinVersion.CURRENT.toString(),
            "postgres_version" to context.databaseVersions.postgresVersion,
            "hypopg_version" to context.databaseVersions.hypopgVersion,
        ),
        "comparison_scope" to "fixed_candidate_space",
        "optimum_certified_in_fixed_space" to result.optimumCertifiedInFixedSpace,
        "limitations" to "Planner-cost optimum only in the declared candidate space and budget model; no runtime or global-optimum claim.",
        "max_indexes" to input?.constraints?.maxIndexes,
        "workload" to input?.let { source -> linkedMapOf(
            "queries" to source.queries.map { linkedMapOf("id" to it.id, "sql" to it.sql) },
            "profiles" to source.profiles.map { profile -> linkedMapOf(
                "id" to profile.id, "weight" to profile.weight, "calls_per_hour" to profile.callsPerHour.toSortedMap(),
            ) },
            "constraints" to linkedMapOf(
                "storage_budget_bytes" to source.constraints.storageBudgetBytes,
                "write_budget_units_per_hour" to source.constraints.writeBudgetUnitsPerHour,
                "max_indexes" to source.constraints.maxIndexes,
            ),
            "table_write_rates" to source.tableWriteRates?.toSortedMap()?.mapValues { (_, rates) -> linkedMapOf(
                "inserts_per_hour" to rates.insertsPerHour, "updates_per_hour" to rates.updatesPerHour,
                "deletes_per_hour" to rates.deletesPerHour, "source" to rates.source.name.lowercase(),
            ) },
        ) },
        "candidate_ids" to candidates.map { it.id },
        "candidates" to candidates.map { candidate -> linkedMapOf(
            "id" to candidate.id,
            "ddl" to candidate.ddl,
            "table" to linkedMapOf("schema" to candidate.definition.table.schema, "name" to candidate.definition.table.name),
            "keys" to candidate.definition.keys.map { key -> linkedMapOf(
                "column" to key.column.name, "direction" to key.direction.name.lowercase(),
                "nulls" to key.nulls.name.lowercase(), "collation" to key.collation, "operator_class" to key.operatorClass,
            ) },
            "estimated_bytes" to candidate.estimatedBytes,
            "budgeted_bytes" to candidate.budgetedBytes,
            "write_units_per_hour" to candidate.writeUnitsPerHour,
        ) },
        "structural_configurations" to result.structuralConfigurations,
        "visited_configurations" to result.outcomes.size,
        "evaluated_sets" to result.outcomes.map { outcome -> linkedMapOf(
            "candidate_ids" to outcome.candidateIds,
            "disposition" to outcome.disposition,
            "cost_by_query_id" to outcome.evaluation?.costByQueryId,
            "metrics" to outcome.metrics?.let(::oracleMetrics),
            "budget_violations" to outcome.budgetViolations.map { it.name }.sorted(),
            "errors" to outcome.errors.map(::oracleError),
        ) },
        "best_robust" to result.bestRobust?.let(::oracleConfiguration),
        "best_mean" to result.bestMean?.let(::oracleConfiguration),
        "statistics" to linkedMapOf(
            "explain_attempts" to context.statistics().explainAttempts,
            "evaluation_attempts" to context.statistics().evaluationAttempts,
            "completed_evaluations" to context.statistics().completedEvaluations,
        ),
        "errors" to result.errors.map(::oracleError),
    )
    try {
        options.output.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(options.output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n",
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    } catch (_: Exception) {
        System.err.println("EXPORT_FAILED: Cannot publish oracle output to a new file.")
        exitProcess(1)
    }
    for (error in result.errors) System.err.println("${error.code}: ${error.message}")
    exitProcess(if (result.status == RunStatus.FAILED) result.errors.firstOrNull()?.code?.defaultExitCode ?: 1 else result.status.exitCode)
}

internal data class OracleOptions(val workload: Path, val output: Path, val deadlineSeconds: Long, val candidateIds: Path?) {
    companion object {
        fun parse(args: Array<String>): OracleOptions {
            require(args.size in 6..8 && args.size % 2 == 0)
            val pairs = args.toList().chunked(2)
            val values = pairs.associate { it[0] to it[1] }
            require(values.size == pairs.size && values.keys.all { it in setOf("--workload", "--output", "--deadline-seconds", "--candidate-ids") })
            val seconds = requireNotNull(values["--deadline-seconds"]?.toLongOrNull())
            require(seconds in 1..86_400) { "A positive explicit deadline of at most one day is required" }
            return OracleOptions(Path.of(requireNotNull(values["--workload"])), Path.of(requireNotNull(values["--output"])),
                seconds, values["--candidate-ids"]?.let(Path::of))
        }
    }
}

private fun oracleConfiguration(value: EvaluatedConfiguration) = linkedMapOf(
    "candidate_ids" to value.evaluation.candidateIds,
    "cost_by_query_id" to value.evaluation.costByQueryId,
    "metrics" to oracleMetrics(value.metrics),
)

private fun oracleMetrics(value: SetMetrics) = linkedMapOf(
    "mean_cost" to value.meanCost, "baseline_mean_cost" to value.baselineMeanCost,
    "mean_ratio" to value.meanRatio, "worst_ratio" to value.worstRatio,
    "profiles" to value.profiles.map { profile -> linkedMapOf(
        "profile_id" to profile.profileId, "normalized_weight" to profile.normalizedWeight,
        "baseline_cost" to profile.baselineCost, "cost" to profile.cost, "ratio" to profile.ratio,
        "gain" to profile.gain, "gain_percent" to profile.gainPercent,
    ) },
    "budget_usage" to linkedMapOf(
        "estimated_bytes" to value.budgetUsage.estimatedBytes, "budgeted_bytes" to value.budgetUsage.budgetedBytes,
        "write_units_per_hour" to value.budgetUsage.writeUnitsPerHour, "index_count" to value.budgetUsage.indexCount,
    ),
)

private fun oracleError(value: AnalysisError) = linkedMapOf(
    "code" to value.code.name, "message" to value.message, "query_id" to value.queryId, "profile_id" to value.profileId,
    "details" to value.details,
)
