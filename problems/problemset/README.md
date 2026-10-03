# Attributed external practice pool

All 358 problems from [iamywl/problemset](https://github.com/iamywl/problemset) at commit `3da07507a3842458aa3576db4ec46cd97348d351` are ordinary shared practice problems. The operator supplied the author's permission to use this collection. Upstream directories cover 31 subjects; display categories use Korean names.

The public [release manifest](../../generation/thinking-problemset-v1.json) records source file hashes, final package/reference hashes, each reviewed thinking profile and adaptation notes. External D labels were not converted into thinking levels. Public statements preserve attribution to the pinned source.

Seven upstream statements began with a blank line. Their restored display titles are bound to the exact frozen package hashes in `backend/src/main/resources/problem-title-overrides.json`; existing judge packages, thinking profiles and submission identities remain unchanged. The manifest includes the restored titles. Staging rejects missing titles except for these exact reviewed repairs.

Private packages, references, hidden tests and verification receipts are kept in the operator's protected release artifact, outside Git. Registration uses `scripts/stage-problemset.py`, then the existing backup-and-transaction workflow in `scripts/apply-basic-pool.sh`. The staging tool requires all 358 reviewed profiles, unchanged upstream files, every supplied input, passing receipts for the exact final packages/references and the current Runner contract. Replaying an identical release changes no existing problem; differing packages or profiles are rejected.

Verification covers the supplied Java references, two upstream sample cases and ten upstream hidden cases per problem, plus regressions for repaired defects. Several ambiguous output contracts and incorrect references were repaired; these changes are recorded per problem. Counting-sort case batches were split without dropping source inputs to keep each output within the existing bound. Large fixed inputs use a separate 6 MiB input bound; output and sandbox resource bounds remain unchanged.

Java references passed the pinned Runner. C++ and Python use the existing STDIO interface with conservative estimated budgets; this release does not claim independent reference or worst-case verification in those languages. Supplied-test agreement is not an exhaustive proof of every allowed input. Selected repaired algorithms also have small-input oracle comparisons and failing-original-code regression evidence in the private artifact.

`python3 scripts/smoke-basic-pool.py <candidate directories...> --language JAVA --external-manifest generation/thinking-problemset-v1.json --output <private report>` checks all imported public profiles and submits selected references through the production HTTP/DB/Runner path, including replay, package hashes, measured memory and hidden-data privacy. Only its synthetic account and submissions are cleaned up.

## 서비스 훈련 코스

`backend/src/main/resources/training-courses-v1.json`은 이 문제 풀의 고정 버전을 연결한 8개 코스다.
입문, 역량검사 탐색·구현, Pro 기반 자료구조·최적화, 자료구조, 탐색, 그래프, 동적 계획법,
이분 탐색·구간 최적화로 구성하며 코스당 3단계/12문제(Pro 18문제)다. 여러 코스에 같은 문제가
포함될 수 있다. 102개 연결은 중복을 포함한 수이며 신규 문제 수가 아니다.

사용자가 제공한 [알고리즘 이론·역량검사 로드맵](https://docs.google.com/spreadsheets/d/1z_fEpwe7ShMo_hxhLqzBoj_-1914lE3Sd_bJLR4RQGA/edit?gid=1264634096)을
읽고 선형 자료구조 → 탐색 → 응용의 단계 구성을 참고했다. 강의 내용·개인 활동·시험 일정은
복제하지 않는다. 공식 기출 또는 Pro 함수 호출형 실전 패키지로 표시하지 않는다.

선택한 코스 정의와 순서는 계정별 DB 스냅샷으로 저장한다. 수정한 코스를 배포할 때는 revision을
올려 기존 등록자의 순서를 보존한다. 진도는 현재 접근 가능한 일반 문제의 본인 정식 AC 기록을
문제별 1회로 집계하며 이전 AC도 포함한다. 실행·오답·다른 사용자·진단·출제 검증 제출은 제외한다.
정답 진도는 숙련도나 합격 인증이 아니다. 훈련 시작·전환은 기존 training_session을 사용한다.
동일 요청의 replay, 현재 세션 fence, 소유권, 문제 보류, 원자적 종료·시작을 서버에서 검사한다.
