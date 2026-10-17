---
name: testlum-generate
description: Write Testlum API tests from a description, a work item or an API spec, including negative cases and variation files. Use when asked to create, add or extend Testlum tests.
---

# Generate Testlum tests

Uses the testlum MCP tools. Tool paths are inside the container, so the tests folder is `/work/...`.

1. Find the input. It can be what the user told you, a work item (read it through the ADO tool if you have one, read-only, otherwise ask for the text) or an API description such as an OpenAPI file or a list of calls.
2. If there is no tests folder yet, call `testlum_scaffold`. Otherwise call `testlum_list_tests` first so you do not duplicate a test that exists.
3. Never guess syntax. Call `testlum_lookup` (no path for the command list, `http/get` or `assert/equal` for one command). The root element needs the xmlns that lookup prints at the end.
4. Write one scenario per behaviour with `testlum_write_test`. Every command needs a `comment` of 10+ characters. Tag the scenario with the ticket id when there is one (`ADO-123,api`), so coverage and cleanup can find it later.
5. Check fields, not whole bodies, when the body has ids or dates: use `assert/jsonPath` with `exists`, `equals` or `matches`. Use `expected_N.json` only for stable bodies.
6. Negative tests: bad input, missing fields, unknown ids, wrong types. Put the real error message from the API in the assertion.
7. Several inputs with the same shape: make one scenario and call `testlum_generate_variations` with the rows. Use `{{column}}` in the scenario.
8. Run `testlum_run` and read the result. A test is not done until it passes against the real app. If it fails, use the testlum-triage skill, do not just edit the test until it goes green.

Expected values come from the ticket or the API description, never from what the app happened to return.
