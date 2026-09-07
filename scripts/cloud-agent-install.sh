#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if ! command -v mvn >/dev/null 2>&1; then
  sudo apt-get update -y
  sudo apt-get install -y maven
fi

if ! command -v node >/dev/null 2>&1; then
  echo "Node.js is required" >&2
  exit 1
fi

cd "$ROOT/server"
mvn -q -DskipTests compile

cd "$ROOT/client"
if [[ -f package-lock.json ]]; then
  npm ci
else
  npm install
fi
