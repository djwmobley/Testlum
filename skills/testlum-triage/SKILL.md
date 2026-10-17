---
name: testlum-triage
description: Work out why a Testlum run failed: is the test wrong or the app wrong. Fix the test or write up the bug. Use when a Testlum run has failures.
---

# Triage a Testlum failure

1. Get the failure from `testlum_run`: the scenario name and the failure text. Mismatch messages name the field, like `$.items[2].price: expected 5 actual 6`. For a body mismatch the engine also writes `action_N_actual.json` next to the scenario.
2. Rerun that one scenario once (`testlum_run` with its tag). If it passes the second time, say it is flaky, name the likely cause (shared data, timing) and stop.
3. Decide against the source of truth, which is the ticket or the API description, not the test and not the app:
   - Test wrong: the expectation does not match the ticket, a variable is wrong, an endpoint changed on purpose, or the data was not seeded. Fix the test with `testlum_write_test` (`overwrite`), then rerun.
   - App wrong: the app breaks what the ticket says. Do not touch the test.
   - Cannot tell: ask the user which side is right. Do not pick one to get a green run.
4. For an app bug, write a short report: scenario, the call made, expected, actual, and the ticket it breaks. If you have an ADO tool and the user asked for it, draft the bug but do not file it without being told.
5. Never loosen an assertion, skip or deactivate a test just to pass.

End with one line per failure: test wrong and fixed, app wrong and reported, or needs a decision.
