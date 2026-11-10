# Декомпозиция: метрики, бюджеты и компаратор (Равиль)

> Рабочая заметка. **НИКОГДА не коммитить.**
> Статус: **T5 ✅**, **T1 ✅** (ветка `feat/weight-normalization`), дальше T2.
> Майлстоун: формулы + фикстуры к 10.11; PoC-стыки к 11.11; проверка на реальных оценках — MVP.

## Подзадачи (порядок исполнения)

| ID | Подзадача | Ветка | Выход | Статус |
|----|-----------|-------|-------|--------|
| **T5** | Fake `ConfigurationEvaluator` + cost-фикстуры (mean-vs-robust, plateau, 0→2, 1→2) | `feat/metrics-fixtures` | seam для поиска Марата | ✅ (PR #…) |
| **T1** | Нормализация весов + инварианты (underflow, sum, конечность) | `feat/weight-normalization` | `WeightNormalizer` (`p_s = w/sum`, ошибки NUMERIC_ERROR) | ✅ |
| **T2** | Метрики профилей и агрегаты `M`/`R` | `feat/profile-metrics` (поверх T1) | `MetricsCalculatorImpl.calculate(...)` → `SetMetrics`; `assessBudget` — заглушка NOT_IMPLEMENTED (закроет T3) | ✅ |
| **T3** | Бюджеты: storage (1.2/ceil) + write units | `feat/budget-assessment` | `assessBudget(...)` → `BudgetAssessment` (заменить заглушку) | ⏳ |
| **T4** | Компаратор: `compare` + `improvesPrimaryMetrics` | `feat/objective-comparator` | детерминированный ключ; гейт greedy | ⏳ |
| **T6** | Zero-baseline регрессия (расширить `BudgetViolation`) | `feat/zero-baseline-regression` | набор с регрессией недопустим | ⏳ |
| **T7** | `priceOfRobustness` из одного пула | `feat/price-of-robustness` | `C_mean(best_robust) − C_mean(best_mean)` | ⏳ |
| **T8** | Кросс-проверка на реальных оценках (Егор/Марат) | интеграционная | числовая сверка, общий пул, консистентность отчёта | ⏳ |

## Git-протокол

- Ветка на подзадачу: `feat/<короткое-название>`. Коммит: `feat: краткое описание`.
- `main` держим синхронным с `origin/main` (`git pull --ff-only`). Новую ветку базируем на свежем `main`; если `main` ушёл вперёд после создания ветки — rebase на `main`.
- `docs/ravil-*.md` — рабочие заметки, НЕ коммитятся (при этом вне `.gitignore`; просто не добавляем в коммит).
- Незакоммиченные чужие правки (например, `settings.gradle.kts` с foojay-плагином) сохраняем через `git stash`, не трогаем и не коммитим.

## Эвристики сокращения перебора

Границы владения: greedy/repair/кэш — Марат (#17/#18), генерация и shortlist-капы — Арсений (#10–#12). В зоне Равиля (#16):
1. `improvesPrimaryMetrics` — строгий детерминированный гейт жадного шага (первые два компонента `(r,m)`/`(m,r)`, округлённые).
2. Value-function ранжирования соседей repair (одиночный gain на метрике) — эффективность лимита 28 слотов.
3. Консистентность `compare` (полный ключ) и `improvesPrimaryMetrics` (первые два компонента).

Запрещено/вне MVP: суммирование одиночных gain в эффект набора, аддитивный per-query кэш, вымышленные размеры/cost без HypoPG, недетерминизм, beam/A*/обучаемый поиск.