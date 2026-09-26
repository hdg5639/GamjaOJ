# Core A candidate review — 2026-09-26

Artifact SHA-256: `5f22c2353adeddf6f915865cbf212ff3ffc2f825eeefe4bb37df8317c541b316`.

This is an engineering/content inspection, not human pedagogical calibration or production release.
The candidate remains `reviewed=false`. Existing progress is tracked only in docs/GamjaJudge_Implementation_Status.md.

| Pair | Checked contract and evidence | Remaining inference limit |
| --- | --- | --- |
| Clock / robot | Day rollover including multiple days; rejected moves retain state and later commands continue. | Time conversion and bounded simulation only; not broad implementation proficiency. |
| Runs / ranges | Single/final runs, reset behavior, inclusive endpoints, negatives, 64-bit sums, maximum declared queries. | Range limits allow direct summation; no prefix-sum efficiency conclusion. |
| Distinct / brackets | Separated duplicates, singleton/all-equal values, crossing types, unclosed/early closing and maximum nesting. | Accept equivalent algorithms; no heap/tree/queue proficiency conclusion. |
| Reach / shortest | Single cell, disconnected grid, all-open maximum grid, required detour and non-square grids. | Related tasks are dependent observations; cannot establish recurring habits by themselves. |

## Reproducible evidence

- Eight Java references passed all 53 fixed cases through the actual Docker Runner.
- Eight original targeted mutants and eight additional mutants returned WA. Additional probes include
  bad hour normalization, stopping on rejected commands, singleton initialization, int overflow,
  invalid distinct-count result, unclosed stacks, restricted traversal and Manhattan-only distance.
- Local authenticated browser test uses the implementation pair with a disposable PostgreSQL database,
  application process and real pull Worker. It verifies custom-run accounting, pause/restart recovery,
  AC advancement, five-WA exhaustion and persisted DB outcomes.
- Fixture-only `reviewed=true` in that disposable database is not approval of the candidate bank.

## Publication boundaries

Public endpoints must exclude hidden tests, references, mutants and rubrics. The staging transaction
leaves the bank unavailable. Keep the current immutable artifact for review; changes to released
statements/test contracts require a new version rather than silently overwriting active snapshots.

Difficulty and completion time need actual learner observations. The current easy/medium labels
are authoring hypotheses. Do not advertise a time estimate, exhaustive coverage or mastery score.
A second grid task after the first can benefit from immediate transfer; preserve ordering and pair
dependence when interpreting evidence. The B bank and exposure-aware reassessment are separate work.
