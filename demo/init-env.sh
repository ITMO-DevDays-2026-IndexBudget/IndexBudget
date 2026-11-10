#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
python3 - <<'PY'
import os
import secrets
from pathlib import Path

path = Path('.env')
if path.exists():
    print('.env already exists; credentials preserved.')
else:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as out:
        out.write('INDEXBUDGET_ADMIN_PASSWORD=' + secrets.token_hex(24) + '\n')
        out.write('INDEXBUDGET_READER_PASSWORD=' + secrets.token_hex(24) + '\n')
        out.write('INDEXBUDGET_DEMO_PORT=55432\n')
    print('Created private .env with random demo credentials.')
PY
