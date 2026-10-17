#!/bin/sh
# Starts the Testlum MCP server for one client session and removes it when the session ends.
# Point the MCP client config at this script. Never leave the container running.
#
#   start-testlum-mcp.sh [TESTS_DIR]     TESTS_DIR is optional and defaults to $PWD, the folder the MCP client starts it in; it is mounted at /work in the container
#   start-testlum-mcp.sh --cleanup       remove any leftover testlum-mcp containers
#
# Env: TESTLUM_MCP_IMAGE (default testlum-mcp:0.2.0), TESTLUM_PASS_ENV (comma list of env var names
# to forward, no values here), TESTLUM_ENGINE_JAR_HOST (engine jar to mount instead of the image's),
# TESTLUM_MCP_IDLE_SECONDS (default 900), TESTLUM_MCP_JAR (server jar for the no-Docker path).
# Without a usable Docker it runs the jar as a plain stdio process (build containers, no socket).
set -u

here=$(cd "$(dirname "$0")" && pwd)
image=${TESTLUM_MCP_IMAGE:-testlum-mcp:0.2.0}
say() { printf '%s\n' "testlum-mcp: $*" >&2; }

if [ "${1:-}" = "--cleanup" ]; then
  command -v docker >/dev/null 2>&1 || { say "docker not found, nothing to clean"; exit 0; }
  ids=$(docker ps -aq --filter label=app=testlum-mcp 2>/dev/null)
  if [ -n "$ids" ]; then
    # shellcheck disable=SC2086
    docker rm -f $ids >&2
  else
    say "no leftover containers"
  fi
  exit 0
fi

tests=${1:-$PWD}

docker_ready() {
  command -v docker >/dev/null 2>&1 || return 1
  if command -v timeout >/dev/null 2>&1; then
    timeout 10 docker info >/dev/null 2>&1
  else
    docker info >/dev/null 2>&1
  fi
}

if docker_ready; then
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    say "image $image not found, building it"
    docker build -t "$image" "$here/.." >&2 || { say "build failed"; exit 1; }
  fi
  set -- --rm -i --name "testlum-mcp-$$" --label app=testlum-mcp --memory 2g --cpus 2 --add-host=host.docker.internal:host-gateway \
    -e TESTLUM_MCP_IDLE_SECONDS="${TESTLUM_MCP_IDLE_SECONDS:-900}" -v "$tests:/work"
  if [ -n "${TESTLUM_ENGINE_JAR_HOST:-}" ]; then
    set -- "$@" -v "$TESTLUM_ENGINE_JAR_HOST:/testlum/testlum-final.jar:ro"
  fi
  oldifs=$IFS; IFS=,
  for name in ${TESTLUM_PASS_ENV:-}; do
    set -- "$@" -e "$name"
  done
  IFS=$oldifs
  exec docker run "$@" "$image"
fi

jar=${TESTLUM_MCP_JAR:-}
for candidate in "$jar" /opt/testlum-mcp/testlum-mcp.jar "$here/../target/testlum-mcp.jar"; do
  if [ -n "$candidate" ] && [ -f "$candidate" ]; then jar=$candidate; break; fi
done
if [ -z "$jar" ] || [ ! -f "$jar" ]; then
  say "Docker is not available and no server jar was found (set TESTLUM_MCP_JAR)"
  exit 1
fi
jar=$(cd "$(dirname "$jar")" && pwd)/$(basename "$jar")
say "Docker not available, running $jar as a plain process"
cd "$tests" || exit 1
exec java -jar "$jar"
