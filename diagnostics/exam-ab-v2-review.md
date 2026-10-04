# A/B target diagnostic v2 — limited pilot review

User supplied 64 authored questions: four eight-item A sets and four eight-item B sets.
This review approves a limited pilot using the exact private artifacts and policy hashes in
`exam-ab-v2-review.json`, after AI-assisted content review and real pinned-Runner qualification.
It is not independent educator review or a learner calibration study.

- A: 32 STDIO questions, Java 8 / C++17 / Python 3.12 references.
- B: 32 Java UserSolution questions using bundles exported from the actual CallablePrograms factory.
- 128 reference language programs, 1,848 correct fixed-case executions, all AC with measured cgroup
  peaks and at least 25% observed wall budget headroom. Most runs use two bounded FUNCTIONAL slots;
  those timings differ from isolated EXCLUSIVE measurements.
- 128 Python logic mutants pass each question's three public examples and then receive private WA.
  B mutants use the original STDIO equivalent; this is distinct from Java callable reference checks.
- Three B range-query questions needed Java budgets of 6, 8 and 6 seconds respectively after the
  initial default-budget headroom/TLE checks. Full fixed tests were rerun under those exact budgets.
- CORE/APPLIED roles are retained independently of legacy EASY/MEDIUM difficulty.
- Whole-set allocation favors unexposed items and less-used sets; replay keeps the original assignment.
  Repeated sets are labeled, without claiming independent skill improvement or set equivalence.
- All 33 rule illustrations are private PNG media bound to their package hashes; original authored
  solutions/tests and the original 3,826-file integrity manifest are unchanged.

The private artifacts contain solutions and hidden tests and must not enter frontend assets or Git.
Publication uses the backed-up staging/release path with exact package hash, rubric, role, execution
metadata and PNG byte guards. Required time-limit analysis is checked before staging. Release SQL
hashes stored canonical package bytes rather than embedding every hidden input in the guard.

Remaining limits: no exhaustive worst-pattern proof, no independent educator/learner study, no
full semantic duplicate/originality audit across the existing catalog, no official exam prediction.
The supplied slow/TLE teaching claims are not newly certified. Runtime delivery status and validation
commands remain only in docs/GamjaJudge_Implementation_Status.md.
