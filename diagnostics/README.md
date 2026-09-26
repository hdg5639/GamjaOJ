# Diagnostic bank authoring

`core-a-v1.json` is a private candidate, not a published assessment. It includes hidden inputs,
reference/mutant source and evaluation rubrics. Never copy it into frontend assets or serialize it
through a public catalog endpoint. `build_core_bank.py` reproduces the exact candidate artifact.

| Entry category | Easy | Medium | Observation boundary |
| --- | --- | --- | --- |
| implementation | Clock normalization | Bounded robot commands | State and boundary handling |
| arrays-strings | Longest equal-character run | Inclusive range sums | Resets, indexing, integer range |
| basic-data-structures | Distinct values | Two-kind balanced brackets | Duplicates, nesting; no required container |
| basic-search | Grid reachability | Shortest grid distance | Visited state and unweighted distance |

There are eight questions and 53 fixed cases. Statements and constraints are Korean; authoring and
rubric instructions are English. First cases are public examples; all other inputs remain private.
The builder computes expected answers separately from Java references. The path oracle uses repeated
relaxation while the Java references use queue traversal. Cases include a necessary detour, unreachable
and single-cell grids, rejected robot moves, day rollover, nonadjacent duplicates, crossing brackets,
negative sums, overflow-sized sums and maximum declared dimensions/query counts.

These pairs are deliberately narrow. The data-structure pair does not measure all queues/heaps/trees;
search does not measure DFS backtracking or all graphs. Related easy/medium tasks are not independent
replications of a habit. Range-sum limits permit direct summation, so do not infer prefix-sum mastery.
Difficulty labels and total duration still need user-facing calibration. No measured completion-time
promise or cross-category mastery score is supported.

## Reproduction and private staging

From repository root:

```sh
python3 diagnostics/build_core_bank.py
python3 -m unittest tests.test_diagnostic_bank
GAMJAOJ_DOCKER_TESTS=1 python3 -m unittest tests.test_diagnostic_bank
python3 scripts/stage-diagnostic-bank.py --output /tmp/core-a-stage.sql
```

The Docker check executes eight reference solutions and eight targeted wrong solutions through the
actual Runner. AC/WA evidence demonstrates those cases only; one mutant per item is not exhaustive
validation. Tests also detect artifact drift, incomplete pairs, duplicate versions and missing rubrics.

Staging emits one transaction, never connects to a database, never overwrites existing version IDs,
and always leaves `diagnostic_bank.reviewed=false`. Existing catalog/help guards reserve these
versions for diagnostics. The bank cannot start until separately reviewed and released; successful
SQL import or automatic reference checks must not set the review flag by themselves.

Before release, review statement/constraint/example consistency, additional adversarial solutions,
rubric observability, difficulty, overlap, source originality and expected user workload. Record the
exact reviewed artifact version and evidence. B-bank correspondence and prior-exposure records must
be supplied before presenting reassessment as independent improvement evidence.

## First limited public pilot

`core-a-v2.json` preserves v1 and changes the ambiguous clock title under new bank/problem IDs.
`core-a-v2-review.json` and `core-a-v2-review.md` record the AI-assisted content review and restricted
pilot decision. Human educator review and learner calibration have not occurred. Difficulty remains
provisional and public entry must retain the pilot explanation. The artifact's `reviewed=false`
remains intentional: importing it never publishes it automatically.

After checking the recorded review and reproducing its checks:

```sh
python3 -m unittest tests.test_diagnostic_pilot_review
GAMJAOJ_DOCKER_TESTS=1 python3 -m unittest tests.test_diagnostic_bank.DiagnosticPilotRunnerTests
python3 scripts/stage-diagnostic-bank.py --bank diagnostics/core-a-v2.json --output /tmp/pilot-stage.sql
python3 scripts/release-diagnostic-bank.py --bank diagnostics/core-a-v2.json --review diagnostics/core-a-v2-review.json --output /tmp/pilot-release.sql
```

Both tools only emit SQL. Apply staging privately, verify the exact stored content, then use the
separate release transaction through the authorized deployment path. Preserve review/artifact/SQL
alongside a private DB backup. `scripts/smoke-diagnostic-pilot.py` exercises the published eight-item
bank with a disposable authenticated account and the actual Runner, verifies private API projections,
and checks zero AI tasks. It deletes only its own account and does not release content itself.

## B pilot correspondence

`python3 -m diagnostics.build_reassessment_bank` reproduces `core-b-v1.json`: eight new tasks
matched to the four A v2 category pairs. `core-b-v1-review.{json,md}` pins both artifacts and explains
additional demands and non-equivalent difficulty. This is AI-assisted content review, not a learner
study or independent human validation. Both graph tasks remain related observations.

`tests.test_diagnostic_reassessment_bank` checks the contract; with GAMJAOJ_DOCKER_TESTS=1 it runs
all B references, two wrong-solution families and unmodified A references against B. Rejected A code
is not itself a measure of learner improvement. B publication and exact A/B mappings are emitted by
`release-diagnostic-correspondence.py` in one transaction, after ordinary private staging. A held or
changed source prevents the entire publication. Previously unseen eligible pairs appear only through
the completed-source reassessment UI; reviewed mapping targets are omitted from the initial catalog.

`python3 scripts/smoke-diagnostic-pilot.py --reassess` exercises A then B through authenticated HTTPS
and the real Runner, with no model call. `GAMJAOJ_EXPECT_B_PILOT=1 scripts/test-browser-auth.sh`
checks actual category selection, B entry and a positive external-exposure report. Both clean only
their own synthetic user. Release progress is recorded only in Implementation Status.
