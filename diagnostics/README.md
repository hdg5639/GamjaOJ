# Diagnostic bank authoring

## A/B target diagnostics v2

`import_exam_bank.py` converts the privately supplied 64-question package into eight unreviewed
candidate banks. B questions use the actual server-generated `CallablePrograms.bundle` driver and
template, with Java-only callable submissions. STDIO equivalents remain validation material.
CORE/APPLIED are observation roles and are never relabeled EASY/MEDIUM.

Family IDs `exam-a-v2` and `exam-b-v2` group four whole eight-question sets each. Under the user row
lock, allocation prefers fewer exposed questions, then fewer prior assignments. Replaying a start
key returns its original set. Repeated sets are labeled; equivalence and official exam predictions
are not asserted. Existing selectable-category diagnostics and reassessment mappings remain intact.

Private illustrations are converted to PNG and stored in `problem_illustration`, bound to the exact
package hash. Current/previously viewed diagnostic access checks and no-store media are reused;
diagnostic images are never copied into public static assets.

`verify_exam_banks.py` checks A references in all three pinned production runtimes, B Java callable
references, Python logic mutants passing public examples before private WA, and cgroup memory.
It uses two bounded FUNCTIONAL slots, so its timing evidence differs from EXCLUSIVE measurements.
The verifier records private evidence and does not publish banks. Artifacts, answers, hidden tests,
images and SQL stay in ignored `diagnostics/private/`. Publication uses a separate content review,
exact hash binding and the existing backed-up release path.

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

## Algo Mix A v1

`private/algo-mix-a-v1.json` is converted from the user-supplied package by `import_markdown_bank.py`
(20 items: ten categories, one EASY and one MEDIUM each; 143 fixed and 6 generated tests). The package
and the converted artifact stay outside Git (`diagnostics/private/` is ignored): the repository is
public and the artifact holds hidden tests, solutions, generators and seeds. Only the review record
with the artifact SHA-256 is committed; keep the artifact with the private DB backup. The candidate keeps C++/Python solutions, slow solutions and generators for
verification only; staging reads problem, category, difficulty and rubric.

```sh
python3 diagnostics/import_markdown_bank.py --markdown <package>/banks/algo-mix-a-v1.md --package <package> --output diagnostics/private/algo-mix-a-v1.json
python3 diagnostics/verify_bank_runner.py --bank diagnostics/private/algo-mix-a-v1.json --output .state/algo-mix-verify.json
python3 scripts/stage-diagnostic-bank.py --bank diagnostics/private/algo-mix-a-v1.json --output /tmp/algo-mix-stage.sql
python3 scripts/release-diagnostic-bank.py --bank diagnostics/private/algo-mix-a-v1.json --review diagnostics/algo-mix-a-v1-review.json --output /tmp/algo-mix-release.sql
python3 scripts/smoke-diagnostic-pilot.py --bank diagnostics/private/algo-mix-a-v1.json --language PYTHON
```

`verify_bank_runner.py` runs the real Runner code and pinned images: correct solutions AC with large-test
time gates, wrong solutions WA after passing the sample, slow solutions TLE only on generated input, and
byte-identical generators across languages. On a Runner host it shares the worker's exclusive host lock.
`algo-mix-a-v1-review.{json,md}` records the AI-assisted review and the two review changes (ASCII minus
in statements; per-test design notes not staged).

## Per-problem execution limits

`algo-mix-time-limits-v1.json` records the 20 Algo Mix questions' Java/C++/Python wall budgets,
algorithm/bounds rationale and historical per-language timing maxima. Each entry binds the exact
v1 and v2 package hashes. V58 applies these budgets to matching stored versions; staging also imports
them when a bank is installed later. Neither path changes statement/test bytes or the review flag.
New diagnostic sessions freeze the budgets; already started sessions and saved submissions keep
their prior limits. Ordinary problem lists and diagnostic questions show the same limits used at admission.

The Runner accepts only a 1–20 second integer wall-budget override of an otherwise exact pinned
language profile. Commands, images, memory and compilation budgets remain server-owned. Deployment
requires the matching server execution contract and Runner build. Generation's independent review
proposes language-specific budgets from complexity and bounds, with measured Java headroom checked
at publication. C++/Python proposals are estimates unless those languages were actually run; trusted
template generation uses a recorded Java timing policy. This is not universal performance calibration.

The existing `verify_bank_runner.py` command automatically uses the hash-bound diagnostic limits,
checks correct/incorrect/slow solutions and writes the selected budgets in its private evidence report.

## 2026-09-30 public-example correction

All 20 Algo Mix questions were checked against their statements: the original 20 samples and
v2's 60 examples have valid input and matching answers. Question 10's three examples all had
M=0, which made the forbidden-pair input format unclear. The corrected problem version
`diagnostic-algo-mix-a-v2-safe-presentation-order-v2` retains the first sample, explicitly explains
that M=0 has no pair lines, and replaces EX2/EX3 with one/two forbidden-pair examples (answers 2/8).
Question 1's `bananabandit` is 12 characters; its input is valid and was not changed.

`audit_algo_examples.py` checks the small public examples' input contracts and computes answers
independently of the reference programs. The corrected 60 examples also passed the Python,
C++17 and Java 8 references (180 executions). These checks cover public examples, not a new
full hidden/generated-test or pedagogy review. Hidden tests, rubrics and time budgets are unchanged.

Reproduce using the private deployed v2 artifact:

```sh
python3 -m unittest tests.test_algo_example_audit
python3 diagnostics/audit_algo_examples.py --bank <private>/algo-mix-a-v2.json
python3 diagnostics/fix_algo_mix_examples.py --source <private>/algo-mix-a-v2.json --output <private>/corrected-bank.json --sql <private>/correction.sql
```

The correction SQL guards the previous package hash and bank assignment, inserts a new problem
version, updates the existing bank's assignment, and migrates only ACTIVE-or-PAUSED/OPEN items without any
submission. It locks the owners against concurrent submission admission and preserves frozen
session time budgets. Previously attempted/skipped/completed items keep their original snapshots.
This is a one-time correction, not a fresh bank staging/release; do not stage the corrected artifact
under the already existing bank ID. Keep generated artifacts and SQL private because they include
hidden test data. Public input-format clarity may expose the targeted mutant in question 10, so its
artifact explicitly opts into `publicExamplesMayRejectMutant`; other items retain the existing rule.
