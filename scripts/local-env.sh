#!/bin/sh
# Run from the repository root. Preserve any existing developer configuration.
set -eu
umask 077
if [ ! -e .env ]; then
  command -v openssl >/dev/null
  printf 'DATABASE_PASSWORD=%s\nADMIN_PASSWORD=%s\nADMIN_USERNAME=admin\nEVENT_RECEIVER_URL=http://localhost:8090/events\n' "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" > .env
  printf '%s\n' 'Created ignored .env with random local credentials. Do not commit or print it.'
fi
