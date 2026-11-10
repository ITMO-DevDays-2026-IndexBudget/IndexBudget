#!/usr/bin/env bash
set -Eeuo pipefail
umask 022

root=${INDEXBUDGET_DEPLOY_ROOT:-"$HOME/indexbudget"}
repository=${INDEXBUDGET_REPOSITORY:-https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget.git}
mkdir -p "$root/releases"
exec 9>"$root/update.lock"
flock -n 9 || exit 0

if [[ -f "$root/update.log" ]] && (( $(stat -c %s "$root/update.log") > 10485760 )); then
    mv -f "$root/update.log" "$root/update.log.1"
fi
exec >>"$root/update.log" 2>&1
staging=
cleanup() {
    result=$?
    trap - EXIT
    if [[ -n "$staging" ]]; then
        rm -rf -- "$staging"
    fi
    rm -f -- "$root/current.next"
    if (( result != 0 )); then
        printf '%s Обновление не выполнено, код %s; current сохранён.\n' "$(date -Is)" "$result"
    fi
    exit "$result"
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

export JAVA_HOME="$root/jdk"
export PATH="$JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin"
export GRADLE_USER_HOME="$root/gradle-cache"
export GIT_TERMINAL_PROMPT=0
test -x "$JAVA_HOME/bin/java"
if [[ ! -d "$root/repository.git" ]]; then
    git init --bare --quiet "$root/repository.git"
fi
fetch_main() {
    git --git-dir="$root/repository.git" fetch --quiet --no-tags "$repository" \
        +refs/heads/main:refs/heads/main
}
fetch_main
revision=$(git --git-dir="$root/repository.git" rev-parse refs/heads/main)
if [[ -f "$root/current/REVISION" ]] && [[ $(cat "$root/current/REVISION") == "$revision" ]]; then
    exit 0
fi

printf '%s Сборка main %s\n' "$(date -Is)" "$revision"
staging=$(mktemp -d "$root/releases/.pending.XXXXXX")
mkdir "$staging/source"
git --git-dir="$root/repository.git" archive "$revision" | tar -x -C "$staging/source"
(
    cd "$staging/source"
    ./gradlew --no-daemon --max-workers=2 build installDist
)
mv "$staging/source/build/install/indexbudget" "$staging/app"
"$staging/app/bin/indexbudget" --help
"$staging/app/bin/indexbudget" --version
printf '%s\n' "$revision" >"$staging/REVISION"
date -Is >"$staging/DEPLOYED_AT"

fetch_main
if [[ $(git --git-dir="$root/repository.git" rev-parse refs/heads/main) != "$revision" ]]; then
    printf '%s main изменился во время сборки; устаревшая версия не активируется.\n' "$(date -Is)"
    exit 0
fi
release="$root/releases/$revision-${staging##*.}"
mv "$staging" "$release"
staging=
ln -s "$release" "$root/current.next"
mv -Tf "$root/current.next" "$root/current"
printf '%s Установлен %s\n' "$(date -Is)" "$revision"
