# Testlum MCP server

An MCP server that helps agents write valid Testlum tests. It reads the engine's own XSD schemas, so it follows the engine and not the wiki. Part 1 has four tools. Writing tests and generating variations come later.

| Tool | What it does |
|---|---|
| `testlum_lookup` | Lists the commands a scenario may hold. With a path such as `http/get` or `assert/equal` it lists attributes (required or optional, allowed values, patterns) and child elements. `kind` can be `global-config` or `integration`. |
| `testlum_scaffold` | Creates the folder layout the engine demands, with valid placeholders, and never overwrites a file. Validates what it wrote. |
| `testlum_check` | Validates a tests folder without running it: XSD check of the global config, each `integration.xml` and each `scenario.xml`, folder layout, expected files exist, include targets exist and carry the `include` tag, http aliases exist, `active="false"` and `onlyThis="true"` flagged. Optional `requireTags` and `tagPattern`. |
| `testlum_run` | Runs the engine headless and returns the exit code with its meaning, per-scenario pass or fail with the full failure text, and an optional scenario-count gate (`expectedScenarios`). Run only some scenarios with `tags`. |

Per-scenario results need the JUnit output of the fork (branch `junit-report`), which the Docker image carries. With the stock engine you get the exit code and the console tail. Console lines are returned with color codes removed.

## Where schemas and the engine come from

- `TESTLUM_ENGINE_JAR`: path to the engine jar. Schemas are read from it and `testlum_run` runs it.
- `TESTLUM_XSD_DIR`: a folder of `schema/*.xsd`, used instead of the jar for schemas.
- `TESTLUM_MCP_IDLE_SECONDS`: exit after this long with no traffic and no call running (default 900). The server also exits when the client closes stdin.

## Never always-on

The client config points at a launcher, not at a running container. The launcher checks that Docker is present and answers `docker info` (10 second limit), builds the image if it is missing, then runs `docker run -i --rm`. The container lives for one client session and is removed when it ends. It is labelled `app=testlum-mcp` and named `testlum-mcp-<pid>`, so leftovers are easy to find: `--cleanup` removes them (D-116).

Without a usable Docker (the D-119 build container has no socket) the same launcher runs the jar as a plain stdio process that ends with the session.

- Windows: `launch/Start-TestlumMcp.ps1 [-TestsDir <folder>]`, cleanup with `-Cleanup`.
- macOS and the container: `launch/start-testlum-mcp.sh [tests-dir]`, cleanup with `--cleanup`.

Inside the container the tests folder is mounted at `/work`, so tool arguments use `/work/...` paths. The launchers forward only the env var names listed in `TESTLUM_PASS_ENV` (for example `QBO_ACCESS_TOKEN`); no value is ever written to a script.

MCP client config (Windows example):

    { "command": "pwsh", "args": ["-NoProfile", "-File", "C:\\path\\to\\mcp\\launch\\Start-TestlumMcp.ps1", "-TestsDir", "C:\\path\\to\\tests"] }

## Reaching a stub or API on the host

Inside the container `127.0.0.1` is the container itself. A stub running on the host must bind `0.0.0.0` (not `127.0.0.1`) and the tests must reach it at `host.docker.internal` (for example `apiUrl` `http://host.docker.internal:8080`). The launchers add `--add-host=host.docker.internal:host-gateway`, so the name also works on Linux. The plain-process path (no Docker) uses `127.0.0.1` as usual.

## Build

    docker build -t testlum-mcp:0.1.0 mcp/          # image on the pinned engine image
    mvn -f mcp/pom.xml package                      # jar, runs the unit tests

Baked into the agent image instead of Docker: copy `testlum-mcp.jar` to `/opt/testlum-mcp/` and set `TESTLUM_ENGINE_JAR`; the shell launcher finds the jar there.

The image carries the fork engine (stock 2.0.0 plus JUnit output, `junit-report` branch at a pinned commit, built in a build stage), so per-scenario results and the scenario-count gate work with nothing mounted. The first build compiles the engine and takes a while. To use another engine, mount its jar with `TESTLUM_ENGINE_JAR_HOST`, or build with `--build-arg ENGINE_REF=<commit>` or `ENGINE_REPO=<url>`.

## Tests

- `mvn -f mcp/pom.xml package`: 12 unit tests against the XSDs in this repo.
- `python3 mcp/e2e/e2e.py mcp/target/testlum-mcp.jar <engine.jar>`: drives the server over stdio against a real engine and a local stub API (scaffold, check, run pass, run fail, tag run, shutdown).

## Notes

- The engine can already read variation data from a CSV named in `<settings><variations>`. Part 2 should build on that.
- `testlum_check` cannot replace the engine's own startup validation (it needs the full Spring context). Run `testlum_run` for the final word.
