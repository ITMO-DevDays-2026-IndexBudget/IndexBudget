# Live-спека: метрики профилей, бюджеты и компаратор (Равиль)

> Рабочая заметка. **НИКОГДА не коммитить.** Источник норм — `IndexBudget-spec-v0.2.md` §4/§7.

## Область
Модуль `MetricsCalculator` + `ObjectiveComparator` в `domain/`. Чистые расчёты, без БД. Единственная реализация формул в проекте.

## Формулы
```text
p_s = weight_s / sum(weight)
C_s(S)    = sum_q f_s(q) * cost(q,S)            B_s = C_s(empty)
gain_s    = B_s - C_s(S)                          # gainPercent только если B_s>0
ratio_s   = C_s(S) / B_s                          # только если B_s>0
C_mean(S) = sum_s p_s * C_s(S)                    # НЕ среднее отношений
B_mean    = C_mean(empty)
M(S)      = C_mean(S) / B_mean                    # если B_mean>0
R(S)      = max_s (C_s/B_s) for s with B_s>0      # НЕВЗВЕШЕННЫЙ максимум
```
`M ≠ sum(p*C/B)`. `R` — худшая нормированная стоимость, не regret. При `B_s=0`: ratio/gainPercent = null, профиль вне максимума `R`, но набор обязан сохранять `C_s=0` (иначе ZERO_BASELINE_REGRESSION). Все `B_s=0` → baseline + пустой набор, `M`/`R` = null, поиск не запускается.

## Компаратор
- `r`, `m` — округлённые до 9 знаков HALF_EVEN; сравнение по округлённым, отчёт хранит исходные.
- Ключи (минимум): `robust: (r, m, bytes, writes?, sorted_ids)`; `mean: (m, r, bytes, writes?, sorted_ids)`.
- `improvesPrimaryMetrics` — только первые два компонента (округлённые); bytes/writes/ids не дают «пользы».

## Бюджеты (цена индекса)
```text
estimated_bytes(i) = hypopg_relation_size(i);  budgeted_bytes(i) = ceil(1.2 * estimated_bytes(i))
row_changes(t) = inserts + updates + deletes в час;  write_units(i) = row_changes(table(i)) * key_columns(i)
```
Два бюджета — раздельные ограничения допустимости, не складываются. Без полного write-профиля `writes` = null везде и пропускается из ключа. `size==null` → кандидат исключается с причиной.

## Зафиксированные решения
- Plateau-фикстура — через округление до 9 знаков HALF_EVEN.
- Zero-baseline регрессия — расширить `BudgetViolation` (`ZERO_BASELINE_REGRESSION`); `ErrorCode` уже есть.
- `priceOfRobustness` = `C_mean(best_robust) − C_mean(best_mean)` из одного пула (helper над двумя `EvaluatedConfiguration`).

## Инварианты (проверки §10)
- `sum(p*C)/sum(p*B) ≠ sum(p*C/B)`; `B_mean=0` при `B_s>0` → ошибка; положительный вес, потерянный из-за underflow, ≠ нулевой вероятности (реализовано в `WeightNormalizer`); нечисловой/отрицательный cost и переполнение → явная ошибка; детерминированные равенства `(r,m) → bytes → writes → ids`.