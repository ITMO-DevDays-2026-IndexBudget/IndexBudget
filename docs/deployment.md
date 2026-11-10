# Автоматическое обновление стенда

Источник обновлений — `main` репозитория `ITMO-DevDays-2026-IndexBudget/IndexBudget`.

## Как происходит обновление

Пользовательский cron раз в минуту проверяет HEAD ветки `main`. Если SHA отличается
от установленного, скрипт [update.sh](../deploy/update.sh) извлекает точный коммит
в отдельный каталог, выполняет `./gradlew --no-daemon --max-workers=2 build installDist`
и проверяет `--help`/`--version`. После успеха атомарно переключает ссылку `current`.
Новый коммит становится доступен после очередной проверки и окончания сборки.

Ошибки сети, сборки, тестов или smoke-проверки не заменяют установленную версию.
Повторная попытка происходит на следующем проходе cron. Параллельные обновления
исключены через `flock`; один проход ограничен 20 минутами с принудительным
завершением через дополнительные 30 секунд. Если `main` изменился во время сборки,
промежуточный коммит не активируется. Сборка следующего коммита начнётся на следующем проходе.

Стенд сам читает публичный репозиторий по HTTPS; GitHub secrets и входящий доступ
из GitHub Actions не нужны. Проверки сборки выполняются на стенде. Отдельного
GitHub deployment status эта схема не создаёт и результата GitHub CI не ожидает.
Для приватного репозитория потребуется отдельный доступ на чтение.

Это установка CLI: в MVP нет постоянно работающего HTTP-сервера. Обычный деплой
не запускает `analyze`, PostgreSQL, seed или реальные индексы. Подключение к БД
задаётся при запуске CLI через `INDEXBUDGET_DATABASE_URL`; сборка его не требует.
Текущее приложение в `main` пока является каркасом: `analyze` возвращает `NOT_IMPLEMENTED`.

Общая demo-БД развёртывается отдельно через
[deploy/db](../deploy/db/README.md):
установочный скрипт, пользовательский cron, постоянный external volume и backup перед
обновлением. Её каталог — `~/indexbudget-db`; она не принадлежит `current`
или `releases` этого CLI-деплоя. Существующее расписание CLI менять не нужно.

## Установка и повторная настройка

Нужен Linux с активным сервисом `cron`, Bash, Git, curl, Python 3, tar,
`sha256sum`, `flock`, GNU `timeout` и `crontab`. Скрипт устанавливается от пользователя
стенда, без `sudo`. Нужен исходящий HTTPS к GitHub, Adoptium, Gradle и Maven.

Из каталога репозитория на локальной машине:

```sh
tar -czf - deploy | ssh machine 'mkdir -p ~/indexbudget-bootstrap && tar -xzf - -C ~/indexbudget-bootstrap'
ssh machine 'bash ~/indexbudget-bootstrap/deploy/install.sh'
```

[install.sh](../deploy/install.sh) скачивает Temurin JDK 25 для архитектуры стенда
и проверяет SHA-256 из API Adoptium перед распаковкой
([официальная инструкция](https://adoptium.net/installation/archives)).
Сведения о скачанном выпуске сохраняются в `~/indexbudget/jdk/download-metadata.json`.
При обрыве загрузки повторная установка продолжает тот же архив из `downloads/`,
используя сохранённые метаданные выпуска и проверяя итоговую контрольную сумму.
Первое успешное развёртывание выполняется до включения расписания. Повторная
установка обновляет скрипты, сохраняет существующий JDK и остальные задания cron;
дубликаты своего задания не создаёт.

Скрипты управления установлены отдельно от приложения. Изменение `deploy/` в
репозитории само по себе не обновляет эти скрипты: для этого повторите установку.
JDK также не обновляется при каждом деплое приложения.

## Использование и диагностика

```sh
ssh machine '~/.local/bin/indexbudget --help'
ssh machine '~/.local/bin/indexbudget --version'
ssh machine 'cat ~/indexbudget/current/REVISION ~/indexbudget/current/DEPLOYED_AT'
ssh machine 'tail -100 ~/indexbudget/update.log'
ssh machine 'crontab -l'
```

На самом стенде:

```sh
~/.local/bin/indexbudget analyze \
  --workload ~/indexbudget/current/source/examples/workload-v2.json \
  --output-dir ~/indexbudget/reports/run-001
```

В текущем каркасе последний пример завершится с кодом 1 и `NOT_IMPLEMENTED`.
Данные и отчёты храните вне `current` и `releases`: эти каталоги принадлежат деплою.
Launcher фиксирует реальный путь релиза перед запуском, поэтому следующее
переключение `current` не меняет classpath уже запущенного CLI.

| Путь на стенде | Содержимое |
| --- | --- |
| `~/.local/bin/indexbudget` | Launcher CLI с настроенным JDK |
| `~/.local/lib/indexbudget-deploy/update.sh` | Установленный скрипт обновления |
| `~/indexbudget/current` | Ссылка на активный релиз |
| `~/indexbudget/releases/<sha>-<suffix>/` | `app/`, `source/`, `REVISION`, `DEPLOYED_AT` |
| `~/indexbudget/repository.git` | Отдельный bare-репозиторий для получения `main` |
| `~/indexbudget/gradle-cache` | Кэш Gradle |
| `~/indexbudget/update.log` | Сборка и ошибки; ротация при 10 MiB в `.1` |
| `~/indexbudget/cron.log` | Ошибки запуска задания до открытия основного журнала |

Старые успешные релизы сохраняются, чтобы не затронуть работающие процессы.
Удалять их можно вручную после проверки, что они не используются; объём `releases/`
и кэша нужно периодически контролировать. После принудительного SIGKILL или
выключения машины может остаться `.pending.*`; он не становится активным релизом.

Для немедленной проверки обновлений:

```sh
ssh machine 'timeout --kill-after=30s 20m bash ~/.local/lib/indexbudget-deploy/update.sh'
```

Для остановки автообновления выполните `crontab -e` на стенде и удалите строку
с маркером `# indexbudget-auto-deploy`. Перед ручным откатом дождитесь окончания
текущего обновления. Ссылку `current` можно переключить на сохранённый успешный
релиз через временную ссылку и `mv -Tf`; при включённом cron следующий проход
снова установит текущий `main`.
