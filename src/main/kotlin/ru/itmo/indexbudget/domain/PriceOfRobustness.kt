package ru.itmo.indexbudget.domain

/** Цена устойчивости среди исследованных наборов, в абсолютных единицах planner cost. */
object PriceOfRobustness {
    /**
     * Использует готовые C_mean двух incumbents; не запускает поиск, EXPLAIN или расчёт метрик.
     * Optimizer обязан передать полностью оценённые допустимые результаты одного запуска и пула,
     * с общими workload, baseline и нормированными весами. SearchResult сам не доказывает общий пул.
     * Для completed/partial возвращает C_mean(best_robust) - C_mean(best_mean), для failed — null.
     * Сохраняет исходную разность без округления, abs или ограничения снизу нулём, включая
     * отрицательный конечный результат; корректность выбора incumbents обеспечивает Optimizer.
     * Нефинитные или отрицательные C_mean дают NUMERIC_ERROR, в том числе у совпавших наборов.
     */
    fun calculate(result: SearchResult): Double? = when (result) {
        is SearchResult.Completed -> difference(result.bestRobust.metrics, result.bestMean.metrics)
        is SearchResult.Partial -> difference(result.bestRobust.metrics, result.bestMean.metrics)
        is SearchResult.Failed -> null
    }

    private fun difference(robust: SetMetrics, mean: SetMetrics): Double {
        if (!robust.meanCost.isFinite() || robust.meanCost < 0.0 ||
            !mean.meanCost.isFinite() || mean.meanCost < 0.0) {
            throw numericError("Incumbent mean costs must be finite and nonnegative.")
        }
        val price = robust.meanCost - mean.meanCost
        if (!price.isFinite()) throw numericError("Price of robustness must be finite.")
        return price
    }

    private fun numericError(message: String) = AnalysisException(AnalysisError(ErrorCode.NUMERIC_ERROR, message))
}
