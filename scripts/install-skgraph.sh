#!/usr/bin/env bash
set -euo pipefail

# Installs private jmjava/skgraph (skgraph-core) into the local Maven repo
# and mirrors it into ./.m2-ci for Docker builds.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${SKGRAPH_DIR:-/tmp/skgraph-src}"
M2_CI="${ROOT}/.m2-ci"

TOKEN="${BROAD_REPO_TOKEN:-${GH_TOKEN:-}}"
if [[ -z "${TOKEN}" ]]; then
  echo "Set BROAD_REPO_TOKEN or GH_TOKEN to clone private jmjava/skgraph" >&2
  exit 1
fi

if [[ ! -d "${DEST}/.git" ]]; then
  rm -rf "${DEST}"
  git clone --depth 1 "https://x-access-token:${TOKEN}@github.com/jmjava/skgraph.git" "${DEST}"
else
  git -C "${DEST}" pull --ff-only || true
fi

(cd "${DEST}" && mvn -pl skgraph-core -am install -DskipTests)

mkdir -p "${M2_CI}/repository"
rsync -a --delete "${HOME}/.m2/repository/com/skgraph/" "${M2_CI}/repository/com/skgraph/" 2>/dev/null \
  || cp -a "${HOME}/.m2/repository/com/skgraph" "${M2_CI}/repository/com/"
# Also need kotlin + transitive deps for offline docker; copy full repo snapshot of needed GAVs is heavy.
# Prefer online Maven Central during docker build; only seed skgraph artifacts offline.
echo "Installed com.skgraph:skgraph-core into ~/.m2 and ${M2_CI}"
