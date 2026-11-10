#!/usr/bin/env bash
set -Eeuo pipefail
umask 022

[[ $(uname -s) == Linux ]] || { echo 'Установка поддерживает Linux.' >&2; exit 1; }
for command in git curl python3 tar sha256sum flock timeout crontab; do
    command -v "$command" >/dev/null
done
systemctl is-active --quiet cron
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
root="$HOME/indexbudget"
scripts="$HOME/.local/lib/indexbudget-deploy"
mkdir -p "$root" "$scripts" "$HOME/.local/bin"
exec 8>"$root/install.lock"
flock -n 8 || { echo 'Установка уже выполняется.' >&2; exit 1; }

if [[ ! -x "$root/jdk/bin/java" ]]; then
    case $(uname -m) in
        x86_64) architecture=x64 ;;
        aarch64) architecture=aarch64 ;;
        *) echo 'Неподдерживаемая архитектура JDK.' >&2; exit 1 ;;
    esac
    staging="$root/downloads/jdk-25-$architecture"
    mkdir -p "$staging"
    if [[ ! -s "$staging/metadata.json" ]]; then
        curl --fail --silent --show-error --location --retry 3 --connect-timeout 10 --max-time 60 \
            "https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=$architecture&image_type=jdk&os=linux&vendor=eclipse" \
            -o "$staging/metadata.json.next"
        mv "$staging/metadata.json.next" "$staging/metadata.json"
    fi
    python3 - "$staging" <<'PY'
import json, pathlib, re, sys
directory = pathlib.Path(sys.argv[1])
package = json.loads((directory / 'metadata.json').read_text())[0]['binary']['package']
assert package['link'].startswith('https://github.com/adoptium/temurin25-binaries/')
assert re.fullmatch(r'[0-9a-f]{64}', package['checksum'])
(directory / 'url').write_text(package['link'])
(directory / 'checksum').write_text(package['checksum'] + '  jdk.tar.gz\n')
PY
    curl --fail --silent --show-error --location --retry 3 --connect-timeout 10 --max-time 1800 --continue-at - \
        "$(cat "$staging/url")" -o "$staging/jdk.tar.gz"
    (cd "$staging" && sha256sum -c checksum)
    mkdir -p "$staging/extracted"
    tar -xzf "$staging/jdk.tar.gz" --strip-components=1 -C "$staging/extracted"
    "$staging/extracted/bin/java" -version
    cp "$staging/metadata.json" "$staging/extracted/download-metadata.json"
    mv "$staging/extracted" "$root/jdk"
    rm -rf -- "$staging"
fi

install -m 755 "$source_dir/update.sh" "$scripts/update.sh.next"
mv -f "$scripts/update.sh.next" "$scripts/update.sh"
install -m 755 "$source_dir/indexbudget" "$HOME/.local/bin/indexbudget.next"
mv -f "$HOME/.local/bin/indexbudget.next" "$HOME/.local/bin/indexbudget"

timeout --kill-after=30s 20m bash "$scripts/update.sh"
test -f "$root/current/REVISION"
cron_file=$(mktemp "$root/.crontab.XXXXXX")
trap 'rm -f -- "$cron_file"' EXIT
if ! LC_ALL=C crontab -l >"$cron_file" 2>"$cron_file.error"; then
    if ! grep -q 'no crontab for' "$cron_file.error"; then
        cat "$cron_file.error" >&2
        rm -f "$cron_file.error"
        exit 1
    fi
fi
rm -f "$cron_file.error"
python3 - "$cron_file" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
marker = '# indexbudget-auto-deploy'
lines = [line for line in path.read_text().splitlines() if not line.endswith(marker)]
lines.append('* * * * * /usr/bin/timeout --kill-after=30s 20m /bin/bash '
             '"$HOME/.local/lib/indexbudget-deploy/update.sh" '
             '>> "$HOME/indexbudget/cron.log" 2>&1 ' + marker)
path.write_text('\n'.join(lines) + '\n')
PY
crontab "$cron_file"
printf 'Автообновление main включено. Текущая версия: '
cat "$root/current/REVISION"
