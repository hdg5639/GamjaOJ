# Attributed external practice pool

All 358 problems from [iamywl/problemset](https://github.com/iamywl/problemset) at commit `3da07507a3842458aa3576db4ec46cd97348d351` are ordinary shared practice problems. The operator supplied the author's permission to use this collection. Upstream directories cover 31 subjects; display categories use Korean names.

The public [release manifest](../../generation/thinking-problemset-v1.json) records source file hashes, final package/reference hashes, each reviewed thinking profile and adaptation notes. External D labels were not converted into thinking levels. Public statements preserve attribution to the pinned source.

Seven upstream statements began with a blank line. Their restored display titles are bound to the exact frozen package hashes in `backend/src/main/resources/problem-title-overrides.json`; existing judge packages, thinking profiles and submission identities remain unchanged. The manifest includes the restored titles. Staging rejects missing titles except for these exact reviewed repairs.

Private packages, references, hidden tests and verification receipts are kept in the operator's protected release artifact, outside Git. Registration uses `scripts/stage-problemset.py`, then the existing backup-and-transaction workflow in `scripts/apply-basic-pool.sh`. The staging tool requires all 358 reviewed profiles, unchanged upstream files, every supplied input, passing receipts for the exact final packages/references and the current Runner contract. Replaying an identical release changes no existing problem; differing packages or profiles are rejected.

Verification covers the supplied Java references, two upstream sample cases and ten upstream hidden cases per problem, plus regressions for repaired defects. Several ambiguous output contracts and incorrect references were repaired; these changes are recorded per problem. Counting-sort case batches were split without dropping source inputs to keep each output within the existing bound. Large fixed inputs use a separate 6 MiB input bound; output and sandbox resource bounds remain unchanged.

Java references passed the pinned Runner. C++ and Python use the existing STDIO interface with conservative estimated budgets; this release does not claim independent reference or worst-case verification in those languages. Supplied-test agreement is not an exhaustive proof of every allowed input. Selected repaired algorithms also have small-input oracle comparisons and failing-original-code regression evidence in the private artifact.

`python3 scripts/smoke-basic-pool.py <candidate directories...> --language JAVA --external-manifest generation/thinking-problemset-v1.json --output <private report>` checks all imported public profiles and submits selected references through the production HTTP/DB/Runner path, including replay, package hashes, measured memory and hidden-data privacy. Only its synthetic account and submissions are cleaned up.
