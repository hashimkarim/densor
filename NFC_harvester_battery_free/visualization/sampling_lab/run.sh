#!/usr/bin/env bash
set -euo pipefail

port=${1-8765}
if (( $# > 1 )) || [[ ! $port =~ ^[0-9]{1,5}$ ]]; then
    echo "Usage: $0 [port (1-65535)]" >&2
    exit 2
fi
port=$((10#$port))
if (( port < 1 || port > 65535 )); then
    echo "Port must be between 1 and 65535." >&2
    exit 2
fi

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
exec python3 serve.py --port "$port" --open
