# Декомпозиция: метрики, бюджеты и компаратор (Равиль)

> Рабочая заметка по [трекеру #16](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/issues/16), не альтернативная спецификация.
> Статус на 10.11.2026: T5 принята в [PR #34](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/pull/34); общий helper T1 — в [PR #32](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/pull/32); T2 принята в [PR #37](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/pull/37); T3 реализована в изменениях #41.
> Формулы и фикстуры нужны к 10.11; PoC-стыки и минимальная сверка на настоящих costs — к 11.11. Полная проверка относится к MVP #21.

## Подзадачи (порядок исполнения)

| ID | Подзадача | Ветка | Выход | Статус |
|----|-----------|-------|-------|--------|
| **T5** | Fake `ConfigurationEvaluator` + cost-фикстуры (mean-vs-robust, plateau, 0→2, 1→2), #38 | `feat/metrics-fixtures` | Тестовый evaluator и полные векторы для поиска | PR #34 принят |
| **T1** | Нормирование весов + инварианты (underflow, sum, конечность) | `main` | Общий `ProfileWeights` для CLI и метрик (`p_s = w/sum`, ошибки NUMERIC_ERROR) | PR #32 принят |
| **T2** | Метрики профилей и агрегаты `M`/`R`, #40 | `feat/profile-metrics` с актуальным `main` | `MetricsCalculatorImpl.calculate(...)` → `SetMetrics` | PR #37 принят |
| **T3** | Бюджеты: storage (1.2/ceil) + write units, #41 | `feat/budget-assessment-41` | `assessBudget(...)` → `BudgetAssessment`, точные границы и явные ошибки | Реализовано, числовые тесты и проверка стыка с pipeline без БД |
| **T4** | Компаратор: `compare` + `improvesPrimaryMetrics` | `feat/objective-comparator` | детерминированный ключ; гейт greedy | PR #47 принят |
| **T6** | Zero-baseline регрессия и baseline-only, #43 | `codex/issue-43-zero-baseline` | `assessEvaluation(...)` → `Accepted(metrics, searchRequired)` / `Rejected(metrics, violations)` | Реализовано в этой ветке; рабочий Optimizer остаётся в #17 |
| **T7** | `priceOfRobustness` из одного пула | `feat/price-of-robustness` | `C_mean(best_robust) − C_mean(best_mean)` | ⏳ |
| **T8** | Кросс-проверка на реальных оценках (Егор/Марат) | интеграционная | числовая сверка, общий пул, консистентность отчёта | ⏳ |

Общий `ProfileWeights` уже доступен в `main`. [PR #35](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/pull/35) закрыт без merge; отдельный `WeightNormalizer` удалён из PR #37. Зависимость T2 от нормирования обеспечивается PR #32.

Контракт T6 отделён от бюджетов T3: после полного EXPLAIN потребитель вызывает `assessEvaluation`, сохраняет `ZERO_BASELINE_REGRESSION` с profile ID при отказе и не обновляет incumbents. При принятом baseline с `searchRequired = false` pipeline возвращает пустой набор без генерации и поиска. Сырые метрики остаются доступны, численные ошибки не превращаются в отказ набору. Подробности и пример потребления для #14/#17 — в [контрактах](contracts.md#допустимость-после-оценки-43). Это не реализация greedy/repair и не подтверждение сквозного анализа с HypoPG.

## Работа с ветками и заметками

- Ветка на подзадачу: `feat/<короткое-название>`. Коммит: `feat: краткое описание`.
- Новую ветку базируем на свежем `main`; существующую ветку PR синхронизируем с ним, сохраняя чужие изменения и опубликованную историю.
- `docs/ravil-*.md` уже входят в репозиторий. Их статусы и ссылки обновляются вместе с затронутым кодом; источники требований — спецификация и issues.
- Чужие незакоммиченные правки не включаем в задачу. Для независимой работы можно использовать отдельный worktree.

## Эвристики сокращения перебора

Границы владения: greedy/repair/кэш — Марат (#17/#18), генерация и shortlist-капы — Арсений (#10–#12). В зоне Равиля (#16):
1. `improvesPrimaryMetrics` — строгий детерминированный гейт жадного шага (первые два компонента `(r,m)`/`(m,r)`, округлённые).
2. Консистентность `compare` (полный ключ) и `improvesPrimaryMetrics` (первые два компонента).

Ранжирование соседей repair относится к #18 и следует §7 спецификации. Новая value-function в декомпозиции метрик не вводится.

Запрещено/вне MVP: суммирование одиночных gain в эффект набора, аддитивный per-query кэш, вымышленные размеры/cost без HypoPG, недетерминизм, beam/A*/обучаемый поиск.
