# A2 / #105: одиночный запуск и серия из демо

Зависимость #101 — фиксированные server-owned datasets — подключена через существующие
Jobs/LocalRunner. Дополнен API v0.2 без изменения обязательных полей старого входа:

```json
{
  "template_id": "04-scenarios",
  "template_version": 1,
  "workload": {"schema_version": 2},
  "series": {
    "schema_version": 1,
    "budget": "storage_budget_bytes",
    "values": [0, 16777216, 67108864],
    "timeout_seconds": 600
  }
}
```

Workload выше сокращён для иллюстрации конверта: в реальном запросе обязателен полный
обычный workload v2. ID/версия должны соответствовать `/api/templates`.
Без series выполняется прежний одиночный analyze. При series выполняется один
analyze-series с файлом запроса v1 и тем же workload; алгоритм, refill, фиксированный
пул и кэш точек реализует CLI. Demo подготавливает датасет только один раз на job.

Режим анализа и ranking из workload передаются явно, включая прежние default normal
и frequency. Итоговый default analyze-series также normal; временный DEEP до чтения
workload не определяет ресурсы итогового запуска.
Frontend не вычисляет рекомендации и не генерирует кандидатов для отдельных точек.

## Вход и UI

Панель бюджетов содержит тип запуска, вид меняемого бюджета, 3–5 значений через запятую
и общий предел 1–86400 секунд. Storage-series задаётся в **байтах**, write-series —
в ед./ч; одиночное поле места по-прежнему в MiB. Значения должны быть разными,
неотрицательными и конечными, storage — целые в диапазоне Long; UI дополнительно
ограничивает storage безопасными целыми JavaScript. Остальные настройки workload не меняются.
Окончательная семантическая валидация выполняется существующим BudgetSeriesReader/CLI.

Для write-series необходим table_write_rates с тремя rates каждой записи. Полнота по
фактически используемым SQL-таблицам проверяется CLI после resolution. Источник и metadata
переданного workload не переписываются; подключение observed-источника к форме относится
к #108. Невалидный конверт — HTTP 400 до очереди; семантическая ошибка CLI — failed job.

Выбор серии сохраняется в localStorage рядом с workload, сохраняется query `?run=UUID`.
Тур панели объясняет одиночный/серийный запуск. При перезагрузке браузера наблюдение
продолжается через прежний GET `/api/runs/<UUID>` без повторного POST.

## Job и публикация

Используется прежняя общая последовательная очередь: queued → preparing → analyzing →
publishing/terminal. Подготовка схемы/seed выполняется единожды из принятого server snapshot.
Job дополнен launch_mode=single/series. Во время серии observer читает атомарный series.json
CLI и сохраняет реальные points/series_status. UI показывает ordinal, budget_value и status
точек. Числовой процент готовности не придумывается. Между публикациями core состояние
может оставаться прежним; это не означает новое вычисление для отображения.

Общий лимит задаёт CLI; внешний предел процесса = series timeout + 60 секунд на завершение.
При process timeout сохранённый series.json/report.html и артефакты публикуются, если они
доступны. Код 0 + completed summary даёт completed job; при ошибке и пригодных артефактах
точек — partial; без пригодных точек — failed. Корневой отчёт/JSON не переписываются demo.

При перезапуске сервера неоконченный job с сохранённым report.html и series.json
восстанавливается как partial с прежними файлами. CLI не запускается повторно и текущая
БД не выдаётся за исходный dataset. Без сохранённого комплекта прежнее поведение — failed
с сообщением о перезапуске. Полный повтор требует нового POST/job UUID.

Публикация сохраняет workload.json, series-request.json, demo-config.json с template
snapshot/run_id/series_id, schema.json и весь CLI output. Job содержит report_url,
series_url, points и cli_exit_code. Единый report.html, series.json, series.html и вложенные
артефакты точек доступны через `/reports/<job UUID>/...`. Разрешены только имена report.html,
report.json, indexes.sql и документированных metadata-файлов; '..' и symlink escape за
корень этого run отвергаются. Локальные ссылки core продолжают работать после переноса
всего каталога. Архив `/reports/` выбирает series.json для серии и report.json для single.

## Проверки и ограничения

```sh
python3 -m unittest discover -s frontend/tests -v
node --test frontend/tests/test_app.js
```

Тесты новой серии используют fake runner для validation, одного prepare/CLI вызова,
partial/timeout, сохранения workload/series и восстановления сохранённых результатов.
Проверки JS валидируют серию, сохранение черновика и неизменность workload. Это не
подтверждение физического анализа.

Реальная приёмка на **отдельном локальном frontend без других пользователей/jobs**:

```sh
./gradlew --no-daemon installDist
docker compose -f frontend/compose.yaml up -d --build --wait
python3 frontend/server.py
# В другом терминале, с доступным Playwright (как для frontend/tests/browser.cjs):
node frontend/tests/series-browser.cjs
```

Скрипт выполняет браузерные single/storage/write запуски, восстанавливает polling после
перезагрузки, открывает артефакты точек и отдельно запускает CLI на том же локальном
датасете для сравнения budgets/selected IDs. Эти дополнительные CLI-запуски — явная
приёмка, а не код отображения. Выход сохраняется в build/frontend-series-verification.
Скрипт намеренно не допускает удалённый URL. Для повторного прогона нужен новый/пустой
каталог build/frontend-series-verification (CLI не заменяет старые артефакты).

## Терминальная публикация и восстановление при ошибке сводки

Общий `series_result` используется штатным завершением и восстановлением job. Он
публикует найденные report.json/HTML/SQL точек даже при отсутствии корневого HTML.
Пригодность требует completed/partial точки с selected.candidate_ids (включая []);
диагностический HTML failed-точки и skipped-точки не дают partial. Без пригодных
наборов итог demo — failed. Завершённый CLI, остановка/внешний timeout и перезапуск
показываются терминальной страницей demo-series.html с причиной и локальными ссылками.
Именно её теперь открывают report_url и архив серии. Ссылка на исходный report.html
есть только при его наличии; это сохранённый снимок core, его running не утверждает,
что процесс всё ещё работает. Исходный series.json не переписывается demo.

Перед публикацией восстанавливаются workload.json, series-request.json, schema.json
из request/status и demo-config.json с demo-run/series ID; повторная подготовка БД не
выполняется. При отсутствии сохранённого каталога schema.json явно отмечает unavailable.

UI проверяет исходную десятичную строку до Number(), HTTP — Decimal до float(),
включая положительный и отрицательный underflow. Настройки series UI восстанавливаются
до первого persist(), поэтому открытие следующей вкладки не сбрасывает режим и бюджеты.
