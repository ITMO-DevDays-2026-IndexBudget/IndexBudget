#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"
export GIT_TERMINAL_PROMPT=0
root=${INDEXBUDGET_DB_ROOT:-"$HOME/indexbudget-db"}
repository=${INDEXBUDGET_REPOSITORY:-https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget.git}
mkdir -p "$root/snapshots" "$root/releases" "$root/backups"
exec 9>"$root/update.lock"
flock -n 9 || exit 0
if [[ -f "$root/update.log" ]] && (( $(stat -c %s "$root/update.log") > 10485760 )); then
    mv "$root/update.log" "$root/update.log.1"
fi
exec >>"$root/update.log" 2>&1
trap 'printf "%s Database updater failed (line %s).\n" "$(date -Is)" "$LINENO"' ERR
[[ -d "$root/app.git" ]] || git init --bare --quiet "$root/app.git"
git --git-dir="$root/app.git" fetch --quiet --no-tags "$repository" +refs/heads/main:refs/heads/main
revision=$(git --git-dir="$root/app.git" rev-parse refs/heads/main)
snapshot="$root/snapshots/$revision"
if [[ ! -f "$snapshot/.complete" ]]; then
    mkdir -p "$snapshot"
    git --git-dir="$root/app.git" archive "$revision" deploy/db demo/postgres | tar -x -C "$snapshot"
    touch "$snapshot/.complete"
fi
bash "$snapshot/deploy/db/deploy.sh" "$root" "$snapshot" "$revision"
