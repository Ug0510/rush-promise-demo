#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
umask 077
root="$(mktemp -d "${TMPDIR:-/tmp}/rush-delivery-test.XXXXXX")"
finish() {
    if pg_ctl -D "$root/data" status >/dev/null 2>&1; then
        pg_ctl -D "$root/data" -w -m fast stop >/dev/null
    fi
    python3 - "$root" <<'PY'
from pathlib import Path
from shutil import rmtree
import sys
root = Path(sys.argv[1])
assert root.name.startswith('rush-delivery-test.')
rmtree(root)
PY
}
trap finish EXIT
port="$(python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(('127.0.0.1',0))
    print(sock.getsockname()[1])
PY
)"
initdb -D "$root/data" -U test_admin --auth-local=trust --auth-host=scram-sha-256 --encoding=UTF8 --locale=C >/dev/null
pg_ctl -D "$root/data" -l "$root/postgres.log" -o "-h 127.0.0.1 -p $port -k $root -c shared_buffers=32MB" -w -t 20 start >/dev/null
export RUSH_DELIVERY_TEST_DB_URL="jdbc:postgresql://127.0.0.1:$port/test_db" RUSH_DELIVERY_TEST_DB_USER=shop_app
RUSH_DELIVERY_TEST_DB_PASSWORD="$(openssl rand -hex 24)"
export RUSH_DELIVERY_TEST_DB_PASSWORD
psql -X -h "$root" -p "$port" -U test_admin -d postgres -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
\getenv test_password RUSH_DELIVERY_TEST_DB_PASSWORD
CREATE ROLE shop_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'test_password';
CREATE DATABASE test_db OWNER shop_app;
SQL
mvn -B -ntp "$@" -Dtest=OutboxPostgresTest,TwilioWhatsAppTest test
