# Core B v1 — limited pilot correspondence review

Decision and exact source/target artifact hashes: core-b-v1-review.json. AI-assisted content review,
not independent human educator review or empirical difficulty calibration. Progress/release evidence
is maintained only in docs/GamjaJudge_Implementation_Status.md.

| A v2 task | B v1 task | Shared observation and interpretation limits |
| --- | --- | --- |
| Forward clock | Minutes before a clock time | Units/day normalization; negative remainder is an additional B demand. |
| Bounded robot | Capacity-limited stock updates | Reject the whole invalid transition, then continue; dimensions and inputs differ. |
| Equal character runs | Strictly increasing contiguous run | Reset/state boundaries; strict comparison and numeric tokens add demands. |
| Range sums | Maximum fixed-length window sum | Inclusive endpoints/64-bit range; negative-only maxima are extra B evidence. No complexity claim. |
| Distinct values | Values appearing exactly once | Duplicate handling; B needs counts, so equal difficulty is not established. |
| Brackets | Nested typed start/end log | LIFO matching; repeated types are permitted, all starts must finish. |
| Grid reachability | Undirected point connectivity | Visited/reachability; adjacency representation changes. |
| Grid shortest path | Minimum undirected hops | Unweighted distance initialization; B graph pair remains dependent evidence. |

All statements specify input bounds, outputs and minimum cases. Fixed cases include backward multi-day
wrap, invalid stock transitions followed by valid ones, equality breaks, last/maximal/negative windows,
nonadjacent duplicates, crossing/unclosed logs, reverse-direction edges, disconnected/one-node/cyclic
and complete graphs. Python expected answers use direct enumeration or reduction/Floyd relaxation;
Java references use state machines, counters/stacks and queue traversal. Alternative correct solutions
remain valid; rubrics do not require one container/algorithm. Original local problem wording is used;
no exhaustive similarity search or novelty claim is made.

The unchanged A source is executed on B to detect accidental reuse acceptance. Rejecting that source
is a useful regression check, not proof of independent learning measurement. Content fingerprints and
positive external-exposure reports retain their limited meanings. Familiar algorithms are expected;
remembered exact solutions/previously seen tasks must be reported.

Only the eight exact source/target versions under the reviewed artifact hashes may be linked. B release
and all mappings are one transaction, requiring the source A bank to be reviewed and available. B is
listed through completed-source reassessment choices, not the ordinary initial-bank catalog. A user
may select only the corresponding complete pairs and must not have prior B assignments.

Release remains a pilot: difficulty labels are provisional, no score delta/improvement certificate,
no completion-time promise and no mastered-category inference. Independent human review, real learner
calibration and new forms after this B bank remain open work.
