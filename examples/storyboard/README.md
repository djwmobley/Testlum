# Storyboard

A tiny story tracker (Node + Postgres) used to try Testlum end to end. The stories are made up.

Start it:

    docker compose up --build

The site is on http://localhost:8080 and Postgres on localhost:5432 (user, password and database are all `storyboard`).

Run the tests from this folder, with the engine jar from the build:

    java -jar testlum-2.0.0.jar "--config=global-config.xml" "--path=<full path to>/tests"

Six scenarios, 16 runs once the CSV variations are counted: listing, lookup by id (5 stories), not found, create, rejected input (5 bad payloads) and moving a story between statuses (3 runs).

The tests were generated with the MCP tools in `../../mcp`, not typed by hand. The one manual edit is the Postgres block in `tests/config/local/integration.xml`.
