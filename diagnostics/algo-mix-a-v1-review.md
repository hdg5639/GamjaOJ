# Algo Mix A v1 — limited pilot content review

Decision: APPROVED_LIMITED_PILOT. Exact artifact hash and machine-readable decision are in
`algo-mix-a-v1-review.json`. Reviewer: AI-assisted content inspection in the authorized implementation
session, not an independent human educator or learner study. The package's own `LOCAL_VERIFIED` and
`LOCAL_BENCHMARK_VERIFIED` labels were not used as evidence; every check below was rerun here.
Operational release evidence belongs only in docs/GamjaJudge_Implementation_Status.md.

## Source and conversion

- User-supplied package `docs/diagnostic-questions` (bank Markdown, tests, three-language solutions,
  slow solutions, generators, manifest). Converted by `diagnostics/import_markdown_bank.py`.
- Every fixed test in the Markdown matches the package test file and manifest SHA-256 byte for byte.
  The Java reference in Markdown equals `solutions/<slug>/java/correct`.
- Staged content: 20 items, 143 fixed tests, 6 generated large tests (3 items, 2 seeds each).
  Verification-only material (C++/Python solutions, slow solutions, generators) stays in the private
  candidate file; staging reads only problem, category, difficulty and rubric.
- Review changes to authored content (no test, solution or generator changed):
  - Statements: U+2212 minus normalized to ASCII `-` (11 statements). Six output specifications said
    to print `−1` while expected outputs contain ASCII `-1`; a learner copying the statement would
    receive WA.
  - Rubric: the per-test design/caution table (`testNotes`) is not staged. It restated test design,
    added 16 KB of evaluation evidence and is not needed for code-evidence interpretation. Test classes
    remain in `testClasses`.

## Review conclusions

- Ten categories, one EASY and one MEDIUM item each: arrays-strings, basic-data-structures, bfs, dfs,
  backtracking, dp, binary-search, greedy, graph, mst (new category label). Statements define input,
  output, empty/unreachable cases and integer ranges; the public sample is the first fixed test.
- Fixed expected outputs were recomputed with the package's separate Python oracles (`qa/oracles.py`,
  not the reference solutions): 143/143 byte-identical, and all 143 inputs pass its input validator.
  These oracles are package-authored, so this is agreement between independent implementations by the
  same author, not an external answer key. Samples were also checked by hand. Correct solutions are AC
  in Java 8, C++17 and Python 3 on the pinned images. Each wrong solution passes the public sample and
  fails a private test with WA, in all three languages. Slow solutions are AC on the package QA's 12
  small random cases (its random_case and oracle) and TLE on generated large inputs, so the
  three efficiency items observe complexity, not only output. A fixed test is not a small case by byte
  size alone: a few-byte input with maximum values is rightly too slow for the slow solution.
- All Runner checks were run on the dedicated Runner host under the worker's exclusive host lock and
  repeated locally. Largest generated-input wall time for correct solutions: Java 495 ms, C++ 254 ms,
  Python 1052 ms (gates 4000/3000/4000 ms).
- Large generated inputs are produced by the Java generator inside the Runner; C++ and Python
  generators emit byte-identical input for each seed.
- Related items share structure (BFS pair, graph pair, MST pair). They are not independent evidence
  of a stable habit; the rubric's `unobservable` and interpretation rules must be kept.
- Alternative correct strategies are listed in `alternatives` (e.g. direct counting instead of prefix
  sums where limits allow, Prim or Kruskal for MST); do not record unused techniques as mastery.
- Workload: 20 items is the full bank; the learner selects categories and can pause. The package's
  100–120 minute estimate is not measured and must be presented as an estimate only.
- Evaluation evidence for a full 20-item session with five Java attempts per item is about 369 KB,
  inside the 512 KiB evidence limit; larger evidence is reduced by the declared source compaction.

## Release checks

Staging stays `reviewed=false`. Release is a separate transaction generated from this exact artifact;
it verifies every pinned package, hash, rubric, category, difficulty, position, runtime, policy,
visibility and review hold before setting the bank flag. Mismatched content fails closed. Answers,
wrong solutions, hidden inputs, generators, seeds, reports, tags and rubrics must stay out of learner
APIs and frontend assets.

## Limitations and remaining work

- No independent human review or learner calibration; difficulty labels are provisional.
- No mastery, validated measurement, time estimate or exhaustive coverage claim.
- No B-bank for this mix, so no reassessment or improvement claim.
- No exhaustive originality or licensing search was performed; the items are standard algorithm
  exercise shapes with package-authored statements.
- A later content change requires new bank and problem version IDs.
