# Документы для разработки и ревью

Начни с [AGENTS.md](../AGENTS.md). Здесь собраны устройство проекта и рабочие правила.

| Документ | Когда нужен |
| --- | --- |
| [Архитектура](architecture.md) | Границы компонентов, поток данных, владение ресурсами. |
| [Контракты](contracts.md) | CLI/JSON, общие модели, метрики, ошибки и артефакты. |
| [Разработка](development.md) | Соглашения Kotlin и проверки изменений. |
| [Ревью](review.md) | Статическое ревью PR без запуска тестов. |

Формулы, алгоритм и лимиты определяет [спецификация v0.2](../IndexBudget-spec-v0.2.md).
Владельцы и зависимости находятся в [плане #2](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/issues/2).
[PoC #22](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/issues/22) и
[MVP #21](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/issues/21) принимаются отдельно.

CLI уже связывает анализ, серии, снимки статистики и импорт с PostgreSQL/HypoPG.
Поддерживаются SELECT и ограниченные UPDATE/DELETE без выполнения DML.
Команды смотри в [README](../README.md), интерфейсы в [docs/contracts.md](../docs/contracts.md),
права в [DML-контракте](../docs/dml-analysis.md). Состояние проверок смотри по сохранённым результатам.

При изменении публичного поведения обновляй спецификацию, примеры и затронутые документы.
Ядро принимает CLI/JSON и сохраняет файлы. Демонстрационная HTTP-обёртка описана в
[frontend](../frontend/README.md) и [спецификации интерфейса](../docs/demo-web-interface.md);
она использует те же формулы и формат workload.
