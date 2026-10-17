# Storyboard

A tiny story tracker (Node + Postgres) used to try Testlum end to end. The stories are made up.

Run the site and the tests with one command:

    docker compose run --rm tests

That builds the images the first time (the engine compile takes a while), starts Postgres and the site, then runs all 16 runs and exits with the engine's exit code. The site is also on http://localhost:18080 and Postgres on localhost:15432 (user, password and database are all `storyboard`) while it is up. Stop it with `docker compose down -v --remove-orphans`.

Six scenarios, 16 runs since each CSV row is a run: listing, lookup by id (5 stories), not found, create, rejected input (5 bad payloads) and moving a story between statuses (3 runs).

The tests were generated with the MCP tools in `../../mcp`, not typed by hand. The one manual edit is the Postgres block in `tests/config/local/integration.xml`, which points at the compose service names.
