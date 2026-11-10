# Рабочая памятка: метрики профилей, бюджеты и компаратор (Равиль)

> Источник норм — [спецификация v0.2](../IndexBudget-spec-v0.2.md), §§4, 7, 9–11; объём работ — [трекер #16](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/issues/16). Эта памятка не меняет требования и не означает готовность всего модуля.

## Область
Модуль `MetricsCalculator` + `ObjectiveComparator` в `domain/`. Чистые расчёты, без БД. Единственная реализация формул в проекте.

Общий `ProfileWeights` принят в [PR #32](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/pull/32) и используется в CLI и расчётах. Отдельный PR #35 закрыт без merge. [PR #37](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/pull/37) реализует только `calculate(...)` для #40; `assessBudget(...)` до T3 возвращает `NOT_IMPLEMENTED`. Компаратор, проверка допустимости после оценки и цена устойчивости остаются отдельными задачами.

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

`calculate(...)` сохраняет абсолютные метрики и при нулевых baseline. Решение о недопустимости регрессии и завершении поиска относится к T6 (#43), а не подменяется обнулением costs.

До расчёта baseline и evaluation должны покрывать ровно query IDs workload. Суммы внутри профиля вычисляются по query ID, агрегаты — по profile ID, чтобы порядок `Map` и входных списков не влиял на результат. Профильные отношения и проценты проверяются на конечность до создания `ProfileMetrics`; `R` использует проверенные отношения.

## Компаратор (T4, #42)
- `r`, `m` — округлённые до 9 знаков HALF_EVEN; сравнение по округлённым, отчёт хранит исходные.
- Ключи (минимум): `robust: (r, m, bytes, writes?, sorted_ids)`; `mean: (m, r, bytes, writes?, sorted_ids)`.
- `improvesPrimaryMetrics` — только первые два компонента (округлённые); bytes/writes/ids не дают «пользы».

## Бюджеты (T3, #41)
```text
estimated_bytes(i) = hypopg_relation_size(i);  budgeted_bytes(i) = ceil(1.2 * estimated_bytes(i))
row_changes(t) = inserts + updates + deletes в час;  write_units(i) = row_changes(table(i)) * key_columns(i)
```
Два бюджета — раздельные ограничения допустимости, не складываются. Без полного write-профиля `writes` = null везде и пропускается из ключа. `size==null` → кандидат исключается с причиной.

## Зафиксированные решения
- Plateau-фикстура — через округление до 9 знаков HALF_EVEN.
- Zero-baseline регрессия требует проверки после EXPLAIN: `assessBudget` не получает baseline/costs, поэтому одного расширения `BudgetViolation` недостаточно. Контракт согласуется в T6 (#43).
- `priceOfRobustness` = `C_mean(best_robust) − C_mean(best_mean)` из одного пула (helper над двумя `EvaluatedConfiguration`).

## Инварианты (проверки §10)
- `sum(p*C)/sum(p*B) ≠ sum(p*C/B)`; `B_mean=0` при `B_s>0` → ошибка; положительный вес, потерянный из-за underflow, ≠ нулевой вероятности (проверяет общий `ProfileWeights`).
- Нечисловой/отрицательный cost и переполнение сумм, произведений, отношений или процентов не превращаются в успешные метрики. Численные ошибки расчёта имеют код `NUMERIC_ERROR`, а профильные — также `profileId`.
- Детерминированные равенства `(r,m) → bytes → writes → ids` относятся к будущему компаратору T4.
