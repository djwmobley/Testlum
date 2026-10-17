# Testlum MCP server

An MCP server that helps agents write valid Testlum tests. It reads the engine's own XSD schemas, so it follows the engine and not the wiki. It has eight tools.

| Tool | What it does |
|---|---|
| `testlum_lookup` | Lists the commands a scenario may hold. With a path such as `http/get` or `assert/equal` it lists attributes (required or optional, allowed values, patterns) and child elements. `kind` can be `global-config` or `integration`. |
| `testlum_scaffold` | Creates the folder layout the engine demands, with valid placeholders, and never overwrites a file. Validates what it wrote. |
| `testlum_check` | Validates a tests folder without running it: XSD check of the global config, each `integration.xml` and each `scenario.xml`, folder layout, expected files exist, include targets exist and carry the `include` tag, http aliases exist, `active="false"` and `onlyThis="true"` flagged. Optional `requireTags` and `tagPattern`. |
| `testlum_run` | Runs the engine headless and returns the exit code with its meaning, per-scenario pass or fail with the full failure text, and an optional run-count gate (`expectedScenarios`, counts runs, so each variation row is one). Run only some scenarios with `tags`. |
| `testlum_write_test` | Writes a scenario (and its request/expected files) after checking it against the schema and the checker. Nothing is left on disk if the check fails. Refuses an existing scenario unless `overwrite` is set. |
| `testlum_generate_variations` | Writes a CSV into `data/` and adds it as the scenario's `<variations>`, one run per row. Checked the same way, rolled back on failure. |
| `testlum_list_tests` | Lists the scenarios in a tests folder: tags (ticket ids), variations file, description, every call as `VERB /endpoint`, every include. |
| `testlum_delete_test` | Deletes a scenario. Refuses while another scenario includes it; removes its variations csv when nothing else uses it. |

Per-scenario results need an engine with the JUnit report and `<junit enabled="true"/>` in the `<report>` of the global config (`testlum_scaffold` writes it). The Docker image carries such an engine. With the stock engine you get the exit code and the console tail. Console lines are returned with color codes removed.

## Where schemas and the engine come from

- `TESTLUM_ENGINE_JAR`: path to the engine jar. Schemas are read from it and `testlum_run` runs it.
- `TESTLUM_XSD_DIR`: a folder of `schema/*.xsd`, used instead of the jar for schemas.
- `TESTLUM_MCP_IDLE_SECONDS`: exit after this long with no traffic and no call running (default 900). The server also exits when the client closes stdin.

## Never always-on

The client config points at a launcher, not at a running container. The launcher checks that Docker is present and answers `docker info` (10 second limit), builds the image if it is missing, then runs `docker run -i --rm`. The container lives for one client session and is removed when it ends. It is labelled `app=testlum-mcp` and named `testlum-mcp-<pid>`, so leftovers are easy to find: `--cleanup` removes stopped ones and leaves running sessions alone.

Without a usable Docker (for example inside a build container) the same launcher runs the jar as a plain stdio process that ends with the session.

- Windows: `launch/Start-TestlumMcp.ps1 [-TestsDir <folder>]` (default: current folder), cleanup with `-Cleanup`.
- macOS and the container: `launch/start-testlum-mcp.sh [tests-dir]` (default: current folder), cleanup with `--cleanup`.

Inside the container the tests folder is mounted at `/work`, so tool arguments use `/work/...` paths. The launchers forward only the env var names listed in `TESTLUM_PASS_ENV` (for example `API_TOKEN`); no value is ever written to a script.

The tests folder is optional. With no argument the launchers use the folder the MCP client starts them in, so one global registration serves every project: whichever project you open is the folder mounted at `/work`. Pass `-TestsDir` (ps1) or a first argument (sh) only to pin a different folder. Set `TESTLUM_WORKDIR` to choose the folder when the client cannot (it is used when no argument is given, before the current folder).

MCP client config, registered once for all projects (Windows example):

    { "command": "pwsh", "args": ["-NoProfile", "-File", "C:\\path\\to\\modules\\mcp\\launch\\Start-TestlumMcp.ps1"] }

macOS or the container:

    { "command": "/path/to/modules/mcp/launch/start-testlum-mcp.sh" }

## Reaching a stub or API on the host

Inside the container `127.0.0.1` is the container itself. A stub running on the host must bind `0.0.0.0` (not `127.0.0.1`) and the tests must reach it at `host.docker.internal` (for example `apiUrl` `http://host.docker.internal:8080`). The launchers add `--add-host=host.docker.internal:host-gateway`, so the name also works on Linux. The plain-process path (no Docker) uses `127.0.0.1` as usual.

## Build

The server is a module of the Testlum reactor, off by default so a normal engine build is unchanged. The `mcp` profile adds it.

    mvn -Pmcp -pl modules/mcp package                                 # jar in modules/mcp/target, runs checkstyle and the unit tests
    docker build -f modules/mcp/Dockerfile -t testlum-mcp:0.5.0 .     # from the repository root

Baked into the agent image instead of Docker: copy `testlum-mcp.jar` to `/opt/testlum-mcp/` and set `TESTLUM_ENGINE_JAR`; the shell launcher finds the jar there.

The image builds the engine from the same checkout, so per-scenario results and the scenario-count gate work with nothing mounted. The first build compiles the engine and takes a while. To use another engine, mount its jar with `TESTLUM_ENGINE_JAR_HOST`.

## Demo

`examples/storyboard` holds a small site, a Postgres database and a set of tests, so you can see the server and the engine work end to end. From that folder, with Docker running:

    docker compose run --build --rm tests

See its README for what it shows and how to switch the sample bug on and off.

## Tests

- `mvn -Pmcp -pl modules/mcp test`: unit tests against the XSDs in this repo.
- `python3 modules/mcp/e2e/e2e.py modules/mcp/target/testlum-mcp.jar <engine.jar>`: drives the server over stdio against a real engine and a local stub API (scaffold, check, run pass, run fail, tag run, shutdown).

## Notes

- `testlum_generate_variations` builds on the engine's own `<settings><variations>` CSV support.
- `testlum_check` cannot replace the engine's own startup validation (it needs the full Spring context). Run `testlum_run` for the final word.
