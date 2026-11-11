# Рабочая памятка: метрики профилей, бюджеты и компаратор (Равиль)

> Источник норм — [спецификация v0.2](../IndexBudget-spec-v0.2.md), §§4, 7, 9–11; объём работ — [трекер #16](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/issues/16). Эта памятка не меняет требования и не означает готовность всего модуля.

## Область
Модуль `MetricsCalculator` + `ObjectiveComparator` в `domain/`. Чистые расчёты, без БД. Единственная реализация формул в проекте.

Общий `ProfileWeights` принят в [PR #32](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/pull/32) и используется в CLI и расчётах. Отдельный PR #35 закрыт без merge. [PR #37](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/pull/37) реализует `calculate(...)` для #40; в #41 реализован `assessBudget(...)`, в [PR #47](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/pull/47) — `ObjectiveComparatorImpl`. В #43 реализован `assessEvaluation(...)`; в #44 — чистый расчёт цены устойчивости, стык с рабочими отчётами остаётся в #19/#20.

## Формулы
```text
p_s = weight_s / sum(weight)
C_s(S)    = sum_q f_s(q) * cost(q,S)            B_s = C_s(empty)
gain_s    = B_s - C_s(S)
gain_percent_s = 100 * gain_s / B_s               # только если B_s>0
ratio_s   = C_s(S) / B_s                          # только если B_s>0
C_mean(S) = sum_s p_s * C_s(S)                    # НЕ среднее отношений
B_mean    = C_mean(empty)
M(S)      = C_mean(S) / B_mean                    # если B_mean>0
R(S)      = max_s (C_s/B_s) for s with B_s>0      # НЕВЗВЕШЕННЫЙ максимум
```
`M ≠ sum(p*C/B)`. `R` — худшая нормированная стоимость, не regret. При `B_s=0`: ratio/gainPercent = null, профиль вне максимума `R`, но набор обязан сохранять `C_s=0` (иначе ZERO_BASELINE_REGRESSION). Все `B_s=0` → baseline + пустой набор, `M`/`R` = null, поиск не запускается.

`calculate(...)` сохраняет абсолютные метрики и при нулевых baseline. `assessEvaluation(...)` из T6 (#43) возвращает `Rejected` при регрессии или `Accepted(searchRequired = false)` для baseline-only, не подменяя costs нулём. Pipeline использует этот сигнал до генерации кандидатов и поиска.

До расчёта baseline и evaluation должны покрывать ровно query IDs workload. Суммы внутри профиля вычисляются по query ID, агрегаты — по profile ID, чтобы порядок `Map` и входных списков не влиял на результат. Профильные отношения и проценты проверяются на конечность до создания `ProfileMetrics`; `R` использует проверенные отношения.

## Компаратор (T4, #42)
- `r`, `m` — округлённые до 9 знаков HALF_EVEN; сравнение по округлённым, отчёт хранит исходные.
- Ключи (минимум): `robust: (r, m, bytes, writes?, sorted_ids)`; `mean: (m, r, bytes, writes?, sorted_ids)`.
- `improvesPrimaryMetrics` — только первые два компонента (округлённые); bytes/writes/ids не дают «пользы».

## Бюджеты (T3, #41)
```text
estimated_bytes(i) = hypopg_relation_size(i);  budgeted_bytes(i) = ceil(1.2 * estimated_bytes(i))
row_changes(t) = inserts + updates + deletes в час;  write_units(i) = row_changes(table(i)) * (key_columns(i) + include_columns(i))
```
Storage, write и max_indexes проверяются раздельно; возвращаются все нарушения, равенство лимиту допустимо. Запас считается целочисленно для каждого нового индекса до суммирования; реальные индексы остаются в baseline. Неизвестный размер даёт `HYPOTHETICAL_INDEX_ERROR` с candidate ID, отрицательный размер или переполнение — `NUMERIC_ERROR`.

Write units накапливаются точно в `BigDecimal` из представленных значений `Double`: сначала rates таблицы, затем умножение на сумму числа ключей и INCLUDE-колонок (#79) и сумма набора. Сравнение с бюджетом выполняется до единственного округления результата в `Double`. Потребители используют готовую допустимость `BudgetAssessment`; округлённое поле расхода не заменяет проверку бюджета. Потеря малых слагаемых, например в `1e16 + 1 + 1`, не допускается.

При отсутствии write-профиля `writes = null` даже для пустого набора; при полном профиле units рассчитываются независимо от включённости лимита. Неполный переданный профиль — `INVALID_WRITE_PROFILE`, не отключение модели. Покрытие всех разрешённых workload-таблиц проверяет pipeline #14 до baseline, наличие rates для кандидатов дополнительно проверяет `assessBudget`. Нулевые storage/max_indexes завершают pipeline с baseline-only; нулевой write budget допускает нулевой расход.

Pipeline сохраняет исходный estimated size и рассчитанные расходы кандидата, а метрики набора сохраняют готовый `BudgetUsage` для отчёта. Кэшированные поля кандидата не используются как источник формул: запас повторно к `budgetedBytes` не применяется. Детали ошибок и границы проверки профиля — в [контрактах](contracts.md).

## Зафиксированные решения
- Plateau-фикстура — через округление до 9 знаков HALF_EVEN.
- Zero-baseline регрессия проверяется после полного EXPLAIN через `assessEvaluation`: `assessBudget` не получает baseline/costs. Контракт T6 (#43) и стык поиска описаны в [контрактах](contracts.md#допустимость-после-оценки-43).
- `PriceOfRobustness.calculate(result: SearchResult)` = `C_mean(best_robust) − C_mean(best_mean)` из одного пула; `Failed` → `null`. Helper использует готовые метрики `Completed`/`Partial`, без округления или подмены отрицательной разности нулём; предусловия и передача в `AnalysisReport.priceOfRobustness` описаны в [контрактах](contracts.md#цена-устойчивости-44).

## Инварианты (проверки §10)
- `sum(p*C)/sum(p*B) ≠ sum(p*C/B)`; `B_mean=0` при `B_s>0` → ошибка; положительный вес, потерянный из-за underflow, ≠ нулевой вероятности (проверяет общий `ProfileWeights`).
- Нечисловой/отрицательный cost и переполнение сумм, произведений, отношений или процентов не превращаются в успешные метрики. Численные ошибки расчёта имеют код `NUMERIC_ERROR`, а профильные — также `profileId`.
- Детерминированные равенства `(r,m) → bytes → writes → ids` проверяются общим `ObjectiveComparatorImpl` из T4.
