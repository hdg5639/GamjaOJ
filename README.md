# GamjaOJ

## 저장소 작업 규칙

- `docs/`는 로컬 문서로 관리하며 Git에 포함하지 않는다.
- `.env`, `.env.*` 등 환경 설정·비밀값 파일은 Git에 포함하지 않는다. 인증 정보와 API 키를 커밋하지 않는다.
- 기본 작업 브랜치는 `main`이며, 별도 요청이 없으면 `main`에서 직접 작업한다.
- 커밋은 기존 Git 사용자 설정으로 작성하며, Codex·Claude 등 AI를 작성자·기여자 또는 `Co-authored-by`에 추가하지 않는다.
- 배포용 서버는 `ssh ocr-serv`로 접속한다. 기존 컨테이너·네트워크·볼륨을 변경하거나 전체 정리하지 않는다.
- 채점 전용 VM은 `ssh runner-serv` (`192.168.0.212`)로 접속한다. 제출 코드는 이 VM에서 실행한다.
- 서비스 통신에는 GamjaOJ 전용 Docker 네트워크를 사용한다. 제출 코드 Sandbox는 `--network none`으로 실행한다.
- 서버에는 내부망으로 접근한다. 도메인 라우팅은 사용자가 별도로 설정할 수 있으므로 도메인을 코드에 고정하지 않는다.
- GitHub 푸시에 포함하는 커밋 메시지와 PR 제목·본문은 아래 형식을 따른다. PR 제목에는 첫 줄을, 본문에는 상세 설명 목록을 작성한다.

```text
feat(기능명):설명
- 상세설명
```

## 구현 단계

첫 단계는 M0: 수동 검증한 문제에 대해 Java 8 코드를 격리 채점한다.
제품 코어는 Spring Boot, 화면은 Next.js, 저장소는 PostgreSQL로 확장한다.
M0의 Python Runner 제어기는 Docker가 있는 호스트에서 실행하는 내부 운영 도구다.
소규모 지인용 가입·로그인·로그아웃과 계정별 설정을 제공한다.
문제 풀이·개인 제출 기록과 영속 채점 작업 큐를 연결했다. 개인 분석/API 예산과 Codex 생성 검증 경로를 추가했으며, 유료 API 활성화와 전용 생성 워커 인증은 운영 설정으로 관리한다. 복습 추천은 후속 단계다.

### 검증된 출제 구조 재사용

생성 화면은 카테고리와 복수 연습 태그를 선택한다. `/api/generation/options`의 서버 목록을 사용하며
`category`와 `tags`로 요청한다. 카테고리별 지원 태그만 허용하고 선택 태그 전체를 출제 지시와
재사용 검색에 반영한다. 태그 순서는 요청 의미를 바꾸지 않으며, 같은 요청 키에 다른 태그를
보내면 409를 반환한다. 기존 `template`/`focus` API는 유지하되 두 요청 형식을 섞을 수 없다.
수열 카테고리는 선택 조건(전체/양수/음수/짝수/홀수) → 변환(원래 값/절댓값/제곱) → 집계(합/개수)를
선언형 계약으로 조합한다. 개수 집계에는 값 변환을 함께 지정하지 않는다. 전체 20가지 규칙 조합이며
기존 기본 합과 괄호 규칙도 유지한다. `/api/generation/selection`에서 실제 계약·제목·입출력 규칙을
유료 호출 없이 확인한다. 조건/변환 중복과 지원하지 않는 조합은 거부한다.

‘다른 규칙 추천’은 선택한 태그를 모두 유지하는 지원 조합을 최대 3개 제시한다.
본인의 최근 12개 진행 중/완료 생성 기록에서 덜 만든 계약을 우선하고 동률은 무작위로 선택한다.
실패한 생성은 반복 횟수에 포함하지 않는다. 추천은 조회만 수행하며 모델 호출·예산 예약·문제 생성을 하지 않는다.
추천을 적용하면 태그와 규칙 미리보기를 갱신하고, 사용자가 생성 버튼을 눌러야 기존 출제 경로로 진입한다.
현재와 다른 조합이 없으면 그 이유를 안내하며 필수 태그를 임의로 제거하지 않는다.
이 추천은 지원 계약 안의 다양화를 위한 것이며 새로운 알고리즘 계약을 만들어내는 기능은 아니다.

개인 생성 요청은 유형의 카테고리·필수 태그·연습 포인트를 저장한다. 같은 사용자의 READY 기록 중
필수 태그를 모두 포함하고 입력 계약·Java 이미지·검증 정책이 같은 구조가 있으면 정답 코드,
테스트 생성기, 입력 검증기와 독립 oracle을 스냅샷으로 재사용한다. 제목·본문·단계형 힌트·해설은
새 테마와 현재 학습 분석에 맞춰 다시 작성한다. 다른 사용자의 문제나 풀이 분석은 재사용하지 않는다.
일치하는 기록이 없으면 기존 Codex 작성과 별도 oracle 작성 경로로 생성한다.

재사용해도 새로운 테스트 입력으로 전체 Runner 게시 검증을 다시 수행한다. 판정이나 검증 완료 상태는
복사하지 않는다. 최초 산출물에서 재사용 코드 변경은 서버가 거부하며, 검증 실패 시에만 기존의
대상별 1회 수정 정책을 적용한다. 호환성 정보가 없는 예전 기록은 재사용 대상에서 제외한다.
완료 검증에는 `executionInputAudit`을 함께 저장한다. 작업별 코드·입력/정답·출력 비교 방식·이미지·
Runner 정책·실행 모드 해시를 보존하고, 이후 구조 재사용의 `reuseAudit`에서 일치/변경 작업을 비교한다.
인스턴스별 문제 version만 비교에서 제외하며 테스트 ID·순서·입력 바이트는 유지한다.
이 기록은 진단 전용이다. 이전 기록에 진단이 없으면 비교 불가로 남기며, 같더라도 검증을 생략하지 않는다.
`execution-input-audit-v2`는 아래 Runner 실행 계약이 해당 attempt와 대조된 경우만 환경 일치로 센다.
본문 계약 검토·근거 무효화 전파·검증 정책 승인은 별도로 필요하다.
검증 완료 근거는 `generation_evidence`에 원본 산출물·문제 package·검증 결과와 해시를 한 번 저장한다.
재사용 의존 관계는 별도 저장하며 선택/생성 결과 수신/검증 진행/게시 시 유효성을 확인한다.
원본 오류를 보류하면 같은 사용자의 모든 파생 문제도 신규 풀이·실행·훈련·분석을 보류하고,
진행 중 문제의 게시를 막는다. 생성 중 모델 결과와 사용량은 수신해 보존한다.
근거를 덮어쓰지 않고 별도 폐기 기록을 남기며 기존 제출·판정·훈련 기록은 유지한다.
태그 생성 목록의 오류 신고에서도 사용할 수 있다. 보류 해제/재인증은 아직 제공하지 않는다.
불변 근거가 없는 이전 완료 기록은 새 코드 재사용 대상에서 제외한다. 과거 의존 스냅샷은
보류 전파에만 사용하며 환경 증명이 있는 새 근거로 소급 인증하지 않는다.
새 수열 조합은 `SequenceRecipe`의 허용된 연산만 해석하며 사용자 코드를 백엔드에서 실행하지 않는다.
입력값은 ±1000만, 길이는 최대1000이며 제곱 합도 long 범위 안이다. 원래 값으로 먼저 필터링한다.
30개 작은 입력 전수 검사, 경계·무작위 입력, 반대 필터/마지막 항 누락 오답과 새 seed 검증을 수행한다.
새 코드와 독립 BigInteger oracle은 계속 Codex가 작성하고 Java 8 Runner에서 검증한다.
계약 ID·명세 해시별로 개인 구조를 적재하므로 서로 다른 연산의 코드를 재사용하지 않는다.
`sequence-recipe-v1`의 연산 의미와 제약은 불변이며, 변경 시 새 계약 버전을 추가하고 기존 버전은 유지한다.
`ProblemContract`는 명세·정답 계산·검증 입력·오답 구분을 제공하는 공통 인터페이스이며,
수열과 그래프 선언이 같은 생성·Runner·게시 경로를 사용한다.

그래프 카테고리는 방향/무방향, 비용1/비음수 가중치, 최단 거리/도달 정점 수/최대 최단 거리의
12가지 계약을 조합한다. N<=60, M<=200, 가중치는0..10억이며 중복·자기 간선·도달 불가를 허용한다.
도달 정점 수에는 출발점을 포함하고 최대 최단 거리는 도달 불가 정점을 제외한다.
독립 oracle은 reference 없이 Floyd–Warshall로 작성하며, reference는 BFS/Dijkstra를 사용하도록 지시한다.
서버의 신뢰된 정답 계산, 작은 그래프 전수 검사,0비용·중복·역방향·590억 거리 경계값,
방향 반전/거리·개수 오답 및 새seed 검사 후에만 게시한다. 작은 검사 영역과 작업 수는 검증 보고서에 남긴다.
가중 그래프와 무방향 비용1 그래프는12개, 방향 비용1 그래프는21개 Runner 작업을 수행한다.
계약 ID와 명세 해시로 재사용을 구분하며 변경 시 기존 버전을 수정하지 않고 새 버전을 추가한다.
임의 문제 명세나 DP 등 모든 알고리즘을 자동으로 생성·검증하는 기능은 아직 지원하지 않는다.

### 자유 출제 초안 · EXPERIMENTAL

‘내 문제 생성 → 직접 요청하기’에서 기존 태그 밖의 요청도 최대2000자로 작성한다.
`/api/generation/spec-drafts`는 본인 초안만 생성·조회하며 같은 요청 키의 재전송을 재사용한다.
Codex CLI가 본문·입출력·제약·예제·기준 풀이 전략·독립 oracle 계획·경계값·mutant 계획을 작성한다.
기존 출제와 동일한 단일 워커/모델 설정/ChatGPT 인증을 사용하며 유료 API fallback은 없다.
한 사용자에게 초안과 일반 출제를 동시에 여러 개 실행하지 않는다. 결과와 사용량·명세 해시를 저장한다.

`QUEUED → GENERATING → DRAFT_READY`는 개인 명세 작성·저장이며 의미·실행 검증이 아니다.
명세의 해시를 고정하여 ‘코드 작성·예비 검사’를 요청하면 기존 Codex 슬롯으로 reference/generator/
inputValidator/힌트/해설을 작성한다. 독립 oracle은 별도 컨텍스트에 문제 명세만 전달하며
reference 코드와 referenceStrategy를 전달하지 않는다.

`BUILD_QUEUED → BUILD_GENERATING → CHECKING → CHECKED`에서는 Java 8 Runner로 예제의
reference/oracle/validator, generator, 생성 입력4개의 reference·oracle 대조와 validator를 실행한다.
총13개 작업이며 generator는 seed를 읽어 완전한 입력 문자열4개의 JSON 배열을 출력한다.
각 입력은4096bytes 이하, generated 대조는 TOKEN_EXACT 기준이다. 호출당 새로운 seed를 사용한다.
검사 작업은 낮은 큐 우선순위를 사용하고 일반 제출의 동시 실행 한도·기록에서 제외한다.
코드·명세·실행 계획·보고서 해시를 저장하며 불일치·잘린 출력·잘못된 생성기 입력은 통과시키지 않는다.

`CHECKED`도 미게시 EXPERIMENTAL 상태다. 숨겨진 ready=false 실행 패키지만 생성하고,
기존 게시 승인 API로 활성화할 수 없다. ‘의미·오답 검증’은 풀이 코드/전략을 전달하지 않는 독립
Codex 컨텍스트로 명세를 검토한다. 모순은 REVIEW_REJECTED와 보완 사유로 남긴다.
경계 사례·오답 반례를 기존 reference/oracle과 대조하고 validator의 정상/잘못된 입력 처리,
서로 다른 오답 코드 2개의 실제 WA를 Runner 6작업으로 확인한다. CE/RE/TLE는 오답 증거가 아니다.
REVIEW_CHECKED에서 ‘최종 검증·내 문제로 게시’를 요청할 수 있다. Codex가 선언한 작은 입력 영역을
서버가 전수 열거(4~12개)하고, 별도 컨텍스트가 영역과 최대 입력 계획을 검토한다.
독립적으로 변하는 선택 축이 최소2개 있어야 하며, 고른 예제 목록만으로 전수 영역을 대신할 수 없다.
기준/oracle 대조, 최대 입력 반복 실행, 새 seed 입력 대조, 실제 게시 테스트 묶음의 반복 실행을
모두 통과해야 PUBLISHED가 된다. 최대 입력이16KiB를 넘거나 검증 계획이 부적절하면 게시를 보류한다.
문제는 본인에게만 [실험]으로 표시하고 기존 풀이·제출·힌트·해설 경로를 사용한다.
전수 검사는 선언된 작은 영역에 한정되며 전체 입력에 대한 정답 증명이 아니다. 자유 출제 구조 재사용은 아직 연결하지 않는다.
검토는 초안당 1회이며 명세/코드/검토 해시와 원응답을 저장하고 재전송 시 재사용한다.
명세 단계는 FAILED, 코드·예비 검사 단계는 BUILD_FAILED, lease 만료는 NEEDS_REVIEW로 남긴다.
새 코드 작성은 초안당1회이며 재전송은 저장된 결과를 재사용한다. 자동 재출제·유료 API fallback은 없다.

## 사용자 계정

본인과 싸피 지인들이 각자의 풀이·학습 기록을 갖는 소규모 서비스로 운영한다.
공유 초대코드로 가입하고 아이디·비밀번호로 로그인한다. 닉네임과 훈련 목표는 계정별로 저장한다.
복잡한 조직·권한 체계, SSO, 소셜 로그인, 이메일 인증은 현재 범위에 넣지 않는다.

- 서버가 발급한 사용자 UUID를 개인화의 기준으로 사용한다. 클라이언트가 보낸 사용자 ID로 소유자를 결정하지 않는다.
- 비밀번호는 BCrypt로 저장한다. 쿠키는 HttpOnly·SameSite=Lax이며 변경 요청에는 CSRF 검증을 적용한다.
- 로그인 세션은 PostgreSQL에 저장하며 앱 재시작 후에도 유지한다. 만료는 12시간이다.
- 로그아웃은 서버 세션을 폐기한다. 로그인·가입 요청은 직접 접속 IP별 분당 30회로 제한한다.
- 초기 계정 복구는 운영자에게 요청한다. 자동 비밀번호 재설정은 아직 없다.

### 웹 실행과 배포

```sh
./scripts/build-web.sh
./scripts/deploy-web.sh
```

Next.js 화면을 정적 빌드하여 Spring Boot가 같은 주소에서 제공한다.
배포는 `ocr-serv`의 `~/gamjaoj/web/`에 별도로 설치한다. 앱과 PostgreSQL만
`gamjaoj_backend` 전용 internal 네트워크에 연결한다. 앱의 내부망 포트 게시에는 별도
`gamjaoj_ingress` 전용 네트워크를 사용한다. DB 포트와 Docker 소켓은 외부에 노출하지 않는다.
기존 M0 CLI와 다른 서비스의 컨테이너·볼륨은 공유하지 않는다.

운영 주소는 https://gamjaoj.gamjabox.cloud/ 이며 `ocr-serv`의 `18081` 포트로 라우팅한다.
내부 원본 주소는 `http://192.0.2.10:18081`이다.
서버의 `~/gamjaoj/web/.env`에 `BIND_ADDRESS`, `HTTP_PORT`, `COOKIE_SECURE`,
`INVITE_CODE`, `DB_PASSWORD`, `PUBLIC_BASE_URL`을 보관한다. 첫 배포에서 무작위 초대코드와 DB 비밀번호를 생성하고
후속 배포에서는 보존한다. 초대코드는 아래 명령으로 확인하여 지인에게 전달한다.

```sh
ssh ocr-serv 'sed -n "s/^INVITE_CODE=//p" ~/gamjaoj/web/.env'
```

운영 설정은 `PUBLIC_BASE_URL=https://gamjaoj.gamjabox.cloud`, `COOKIE_SECURE=true`다.
로그인에는 HTTPS 운영 주소를 사용한다. Secure 쿠키는 내부 HTTP 주소로 전송되지 않는다.
인증·채점·브라우저 검증은 운영 주소를 읽으며 워커 API는 기존 내부망 연결을 유지한다.
도메인은 애플리케이션 코드에 고정하지 않는다.
프록시 헤더는 기본적으로 신뢰하지 않으며 프록시 뒤에서는 요청 제한도 해당 프록시 IP 기준이다.

배포 스크립트는 새 이미지 기동 후 임시 계정으로 가입·로그인·계정 분리·로그아웃을 검증하며,
앱 컨테이너를 한 번 재시작해 세션 유지도 확인한다. 임시 계정은 검증 종료 시 삭제한다.
이 소규모 배포에는 짧은 앱 중단이 발생할 수 있다.

실제 브라우저 검증은 `frontend`에서 `npx playwright install chromium`을 한 번 실행한 뒤
`./scripts/test-browser-auth.sh`로 수행한다. 임시 계정의 가입·잘못된 비밀번호·로그인·설정 저장·
응답 유실 뒤 제출 재전송·실제 Java 채점·새로고침·로그아웃·모바일 화면을 확인하고 해당 임시 계정만 정리한다.

## 문제 풀이·제출 기록

로그인하면 바로 풀이 화면으로 들어간다. 왼쪽 문제·오른쪽 편집기에서 코드를 작성하고,
편집기 아래 결과 영역의 `실행 테스트` / `제출 기록`을 전환한다.
`입력 테스트`는 입력창으로 이동하며 예제 입력은 실제 기본값으로 채워진다.
설정은 우측 상단 `내 설정`, 훈련 시작·종료·복기는 `훈련 기록` 화면에서 관리한다.
CodeMirror supports Java, C++ and Python syntax; Java additionally highlights method/parameter declarations. 줄 번호, 문법 색상,
괄호 자동 완성·짝 표시, 자동 들여쓰기, 코드 접기와 실행 취소/다시 실행을 지원한다.
Ctrl/⌘+F로 코드 내부를 검색한다. 타입 추론·메서드 시그니처 도움말이나 Java 8 문법 진단은
별도 언어 서버 없이 제공하지 않으며, 실제 컴파일 결과는 제출/실행 결과에서 확인한다.
Problem/editor dividers and the grip below the editor support dragging and arrow keys. The
`편집기 크기` menu provides sliders and reset. Ordinary and diagnostic sizes are stored separately
per account in this browser; mobile supports height adjustment. Resizing preserves code and inputs.

파일 도구는 접힌 메뉴로 제공한다. Tab은 4칸 들여쓰기, Ctrl/⌘+Enter는 제출이다.
Esc 다음 Tab으로 편집기 밖으로 이동할 수 있다. 모바일은 `문제 보기` / `코드 작성`으로
전환하며 코드·직접 입력·실행 결과를 유지한다. 데스크톱에서는 두 영역을 동시에 표시한다.
로그인한 데스크톱(1024px 이상)은 최상단 계정 바를 기본으로 접어 60px를 풀이에 사용한다.
내용의 스크롤 맨 위에서 위로 스크롤하면 펼쳐지고 아래로 조금 내리면 다시 접힌다.
상단 중앙 손잡이로도 열고 닫을 수 있으며, 계정 메뉴에 키보드 초점이 있으면 자동으로 접지 않는다.
모바일과 로그인 전 화면에서는 상단 바를 유지한다.
풀이 공간은 화면 높이에 맞춰 표시한다. 실행 테스트·제출 기록은 기본으로 접혀 있으며,
탭을 누르거나 코드를 제출하면 열린다. 1440px 이상에서는 문제·코드·결과를 나란히 표시하고,
경계선을 드래그하거나 방향키로 결과 폭을 조절한다. 중간 화면에서는 결과가 문제 영역을 대신하며
코드를 가리지 않는다. 모바일은 문제·코드·결과를 전환한다. `결과 접기`로 원래 배치로 돌아간다.
실행·제출 내역은 기본 접힘, 펼치면 10개씩 표시하며 기록 선택 후 목록을 접는다.
과거 코드는 읽기 전용 편집기로 확인하고 작성 중인 초안으로 돌아올 수 있다.
제출 버튼은 편집 영역 크기와 별개로 유지한다.
긴 코드·문제·결과는 각 영역 안에서 스크롤하며, 선택한 기록은 목록에서 구분된다.

로그인 후 `풀이할 문제`에서 수동 검증 문제 3개를 선택할 수 있다.
상단 `문제 목록`에서는 제목·문제 ID로 검색하고 풀이 화면으로 이동한다. 모바일에서는 선택한 문제 본문을 먼저 표시한다.
목록을 오가도 문제별 초안·진행 중인 훈련을 유지하며, 제출 접수 확인 중에는 문제 변경을 막는다.

| 문제 버전 | 문제 | 연습 내용 |
| --- | --- | --- |
| `sum-v1` | 두 정수의 합 | 기본 입출력 |
| `total-v1` | 수열의 합 | 반복문과 long 합계 |
| `valid-parentheses-v1` | 올바른 괄호 | 순회 중 상태와 접두 조건 |

문제별 본문·예제·직접 실행 기본 입력이 함께 전환된다. 진행 중인 훈련이 있어도 다른 문제를 선택할 수 있다. 훈련과 같은 문제의 작업만
해당 훈련에 연결하고 다른 문제는 자유 풀이로 저장한다. 요청 처리·제출 접수 확인 중에는
문제 선택을 잠그고 이유를 표시한다. 기존 문제 버전은 수정하지 않고 새 버전만 추가한다.
정답 풀이는 `examples/total/`, `examples/valid-parentheses/`에서 관리한다.
`python3 -m unittest -v tests.test_problem_catalog`는 독립 정답 검증을,
전용 Runner에서 `GAMJAOJ_DOCKER_TESTS=1`을 설정하면 Java 8 정답/대표 오답 검증을 수행한다.
운영 API·DB·상시 워커 경로는 `python3 scripts/smoke-dedicated-runner.py --catalog-only`로 검사한다.

작성 중인 초안은 사용자 UUID·문제 버전별로 이 브라우저의 localStorage에 자동 저장한다.
새로고침·로그아웃 후 같은 계정으로 돌아오면 복원하며, 빈 편집 내용도 보존한다.
초안은 서버 동기화 대상이 아니므로 다른 기기·브라우저로 옮길 때는 `Main.java 내려받기`를 사용한다.
여러 탭에서 같은 문제를 편집하면 마지막으로 편집한 탭의 내용이 저장된다.
브라우저 저장이 차단되거나 용량이 부족하면 저장 실패를 알리고 편집·파일 내려받기는 유지한다.
`Java 파일 불러오기`는 UTF-8 `.java` 파일(최대 64 KiB)을 편집기에 가져오며 자동 제출하지 않는다.
잘못된 형식·크기의 파일은 현재 초안을 덮어쓰지 않는다. 클래스 이름은 계속 `Main`을 사용한다.
제출하면 정확한 코드·해시·문제 버전과 채점 job을 같은 DB 트랜잭션으로 저장한다.
내 최근 제출 50개와 각 제출의 원본 코드·판정·컴파일 오류를 조회할 수 있다.
숨은 테스트·정답·런타임 내부 로그는 일반 API에 포함하지 않는다.

- 같은 사용자와 `Idempotency-Key`의 재전송은 같은 제출을 반환한다. 코드나 문제 버전이 다르면 409다.
- 브라우저는 응답이 불확실한 제출의 키와 코드 스냅샷을 탭의 계정별 sessionStorage에 보존한다.
  새로고침 후에도 같은 제출을 재확인한다. 이후 의도적인 새 제출에는 새 키를 사용한다.
  접수 확인 중에 수정한 최신 초안과 원래 제출 스냅샷은 분리하여 보존한다.
- 워커는 짧은 트랜잭션으로 작업을 가져가고 60초 lease를 10초마다 갱신한다.
  유효한 작업이 있는 워커가 재접속하면 같은 attempt를 재개한다.
- 만료된 attempt의 heartbeat·결과는 반영하지 않는다. 최대 3회 시도 후 반복 단절은 IE로 끝난다.
- 개인 생성 문제는 같은 문제 버전·소스 SHA-256·고정 Java 이미지·컴파일 옵션의 성공한 컴파일 산출물을
  워커 메모리에서 재사용한다(최대 16개/32 MiB, 유효기간 10분, 재시작 시 폐기).
  태그 출제의 `generated-UUID-rN`과 자유 출제의 `experimental-check-UUID`를 대상으로 한다.
  자유 출제 UUID는 소유자가 고정된 초안 단위이며, 같은 초안의 변경된 소스도 해시가 다르면 다시 컴파일한다.
  다른 개인 문제·수정 버전·공용 문제와 공유하지 않으며 컴파일 실패/잘못된 아카이브는 저장하지 않는다.
  테스트 입력과 판정은 캐시하지 않는다. 매 테스트의 새 격리 컨테이너, 전체 게시 검증, 사용자 제출 우선순위를 유지한다.
  Runner 보고서의 `compile.cache_hit`으로 재사용을 구분하며 `compile.wall_ms`는 최초 컴파일의 증거다.
- 실행 결과는 워커 로컬에 먼저 저장한다. 결과 저장 후 응답 유실은 같은 결과를 재전송하며 Java를 다시 실행하지 않는다.
- 서버는 저장한 소스·문제·런타임·정책 및 테스트 결과가 일치하는지 검사하고 한 번만 완료 처리한다.
- 한 계정의 미완료 제출은 최대 3개다. 현재 기록은 제출 기록이며 AI의 약점 분석·숙련도 평가가 아니다.

### 직접 입력 실행

편집기 아래 `직접 입력으로 실행`에서 입력을 작성하고 `직접 실행`을 누르면 현재 코드를 전용 Runner에서 실행한다.
빈 입력도 가능하며 UTF-8 기준 입력은 16 KiB, 코드는 64 KiB까지 받는다.
정답 데이터와 비교하지 않으므로 정상 종료는 `실행 완료`이고 AC/WA로 기록하지 않는다.
컴파일 오류·실행 오류·시간/메모리/출력 초과는 구분하여 보여 준다.

- 입력·코드·실행 계획을 함께 저장한다. 실행 기록에서 당시 입력·코드·표준 출력·표준 오류를 다시 확인한다.
- `/api/runs`의 개인 실행 기록은 `/api/submissions`의 정식 제출 기록과 구분한다.
  계정당 미완료 작업 제한 3개는 두 종류를 합산한다.
- 접수 응답을 잃어도 같은 키와 저장된 코드·입력으로 재확인한다. 다른 입력을 같은 키로 보내면 거부한다.
- 실행 계획에는 사용자가 입력한 테스트 한 개만 넣고 숨은 테스트나 정답 데이터를 전달하지 않는다.
  기존 격리·lease·재시도·결과 재전송·소유권 검증을 함께 사용한다.
- 표준 출력은 앞 16 KiB, 표준 오류는 앞 4 KiB를 표시한다. 실행 중 stdout/stderr 합산 64 KiB를 넘으면 출력 초과다.
  긴 표준 출력은 일부만 표시했음을 알린다. 정식 채점의 숨은 테스트 출력은 공개하지 않는다.
- 새 직접 실행 정책은 `java8-run-v1`, 정식 채점 정책은 `java8-judge-v1`이다.
  V5부터 제출별 런타임 이미지·정책을 저장한다. 기존 Java 21 기록은 원래 런타임을 보존한다.
  V38 also pins the language and full execution profile. For a Runner contract change, pre-pull images,
  gracefully stop the worker, deploy the app, install the matching worker, and verify real jobs.

### 훈련 세션

`훈련 기록` 화면의 `훈련 시작`으로 현재 문제와 목표를 고정하면 이후 정식 제출·직접 실행이 해당 훈련에 연결된다.
계정당 진행 중인 훈련은 하나이며, 새로고침·재로그인·앱 재시작 후에도 이어서 진행한다.
시작하지 않은 자유 풀이와 기존 기록은 세션에 임의 편입하지 않는다.

- `훈련 마치기`에서 메모를 남긴다. 종료 시각·목표·메모와 실제 제출/정답/직접 실행/처리 중 개수를 조회한다.
- 종료 전에 접수한 작업은 계속 채점한다. 종료된 세션에는 새 작업을 추가할 수 없지만 이미 접수한 요청의 재확인은 가능하다.
- 시작·종료 응답이 유실되면 저장한 원래 요청으로 재확인한다. 같은 시작 키의 목표나 문제, 종료 재요청의 메모가 다르면 거부한다.
- 세션·제출·직접 실행은 같은 사용자와 문제 버전일 때만 연결한다. 탭 간 종료/제출 경합은 사용자 행 잠금으로 직렬화한다.
- `내 훈련 기록`은 최근 20개, 세션의 작업 목록은 최근 50개를 보여 준다. 개수는 해당 세션의 전체 작업 기준이다.
  연결한 작업에서 당시 코드와 실제 결과를 다시 볼 수 있다.
- 탭을 열어 둔 시간을 실제 학습 시간이나 실력으로 평가하지 않는다. AI 분석·추천·실전 모드는 아직 제공하지 않는다.
- API는 `/api/training-sessions`, `/{id}`, `/{id}/end`다. 제출·직접 실행의 선택 필드 `sessionId`가 없으면 자유 풀이로 저장한다.

### AI 분석·문제 생성

`내 문제 생성`에서 `태그로 만들기` 또는 `직접 요청하기`를 선택한다.
화면 내 방식 전환은 작성 중인 입력을 유지한다. 데스크톱은 입력과 진행·결과를 나란히 표시하고,
작은 화면은 `진행·결과로 이동`으로 이동할 수 있다. 이전 완료 기록은 제목을 눌러 펼친다.
자유 출제는 초안 확인 후 코드 작성·의미 검증·최종 게시를 각각 요청한다.

수열 합 출제는 간결한 Java 표준 라이브러리 구현을 우선해 불필요한 코드 작성량을 줄인다.
모델·reasoning·독립 oracle·게시 검증은 유지하며, 생성 usage에 작성 프로필·단계별 시간·산출물 크기를 저장한다.
실제 종단 검증 스크립트는 생성 단계 시간과 Runner 검증 시간·컴파일 재사용 횟수를 함께 기록한다.

GPT API는 **개인 풀이 분석·추가 맞춤 힌트·문제 소재 구상**을 담당한다. 문제 코드/정답/테스트는 Codex CLI가 작성하며 유료 출제 fallback은 하지 않는다.
기본 모델은 `gpt-5.6-luna` / `low`, 운영자가 명시적으로 요청하는 재분석은 `gpt-5.6-terra` / `medium`이다.
Responses API의 구조화 응답을 사용하며 실패 시 다른 모델로 전환하지 않는다. 피드백은 Runner 판정을 바꾸지 않는다.

- 제출 기록을 열어 분석·맞춤 힌트를 요청한다. 같은 사용자·제출·채점 해시·문제 해시·분석 설정·질문은 재사용한다.
- 훈련 종료 후 미완료 채점이 모두 끝나면 마지막 정식 제출 하나만 분석한다. 매 WA에는 호출하지 않는다.
  시스템 오류 IE와 직접 실행은 자동 분석에서 제외한다. 기능 도입 전에 종료된 훈련은 소급 분석하지 않는다.
- 기본 단계형 힌트와 해설은 저장된 문제 자료를 읽으며 API를 호출하지 않는다.
- `AI_API_ENABLED=false`가 기본값이다. 비활성·예산 부족 시 요청을 보류하고 기존 풀이/채점은 유지한다.
- 서비스 전체 월 예산은 `AI_MONTHLY_BUDGET_USD=10` USD다. UTC 달력 월 기준이며 구독료·서버 비용은 제외한다.
  호출 전 DB 잠금 아래 출력 토큰 상한과 보수적 입력 상한의 비용을 예약한다. 실제 사용량으로 정산하며,
  응답 유실·중단의 미정산 예약은 월이 바뀌어도 유지한다. 명시적 재시도는 별도 시도/비용 기록이다.
- 운영자 `훈련 기록`의 API 예산 영역에서 사용액·미정산 예약·80% 경고를 확인한다.
  이것은 앱의 호출 제한이며 OpenAI 자동 결제·충전을 설정하지 않는다.
- `AI_OPERATOR_USERS`는 쉼표로 구분한 로그인 아이디이며 기본값은 빈 목록이다. 예산 관리·상위 모델 재분석만 운영자 전용이다. 문제 생성에는 운영자 지정이 필요 없다.

설정 전송은 키를 출력하지 않으며 Runner에는 모델 인증을 전달하지 않는다. 아래 명령은 앱 설정만 변경하고 배포하지 않는다.

```sh
# 최초 설정: 유료 호출 비활성 유지
python3 scripts/configure-ai.py
# 키/전체 예산을 확인한 뒤 명시적으로 활성화 (계정 권한 지정과 별개)
python3 scripts/configure-ai.py --budget-usd 10 --enable
# 선택: 예산 조회·상위 모델 권한을 지정할 때만 --operators YOUR_LOGIN_ID 추가
./scripts/deploy-web.sh
```

모델·reasoning은 `AI_DEFAULT_MODEL`, `AI_DEFAULT_REASONING`, `AI_STRONG_MODEL`, `AI_STRONG_REASONING`으로 변경한다.
단가는 해당 슬롯의 `INPUT_USD_PER_M`, `CACHED_USD_PER_M`, `OUTPUT_USD_PER_M`, `PRICING_VERSION` 접미사 환경변수로 관리한다.
내장 단가가 없는 모델은 단가 설정 전 호출하지 않는다. 기본 standard 단가는
[공식 모델 목록](https://developers.openai.com/api/docs/models)의 Luna/Terra 요금에 근거한다.
미정산 비용은 운영자가 제공자 사용량과 대조하기 전 0으로 해제하지 않는다.

문제 생성은 `generation.GenerationAdapter` 인터페이스의 Codex CLI 구현 하나만 제공한다.
`CODEX_GENERATION_MODEL=gpt-5.6-sol`, `CODEX_GENERATION_REASONING=medium`이 초기 설정이다.
품질 검토 후 운영자가 `gpt-6-astra` / `low` 또는 `medium`으로 변경할 수 있다. 자동 승격은 없다.

- 허용 템플릿은 수열 합 `sequence-sum-v1`과 올바른 괄호 `parentheses-v1`이다. 새 이야기·본문, reference, seeded generator, 입력 검증기,
  기본 힌트 3개와 해설을 작성한다. 의미/입력 범위는 각 유형의 검토된 고정 규칙을 사용한다.
- oracle은 별도 `codex exec`, 별도 디렉터리에서 고정 문제 정의만 받는다. reference·작성 대화는 주지 않는다.
- 전용 ChatGPT 인증만 허용하고 API 키 인증으로 대체하지 않는다. CLI 허용 버전은 `0.154.0`과 `0.155.1`이다. 실제 실행 버전은 사용량 메타데이터에 기록한다.
- 전체 생성 작업은 한 개씩 진행하고 검증 실패 후 수정은 문제당 최대 한 번이다. 실패한 산출물만 수정하고 나머지는 그대로 보존한다. 새 수정도 모든 Runner 검증을 다시 거친다.
- 로그인한 사용자가 `내 문제 생성`에서 문제 유형과 학습 포인트를 선택한다. 수열 합은 기초/오버플로/엣지 케이스,
  괄호는 기초/접두 균형/엣지 케이스를 지원한다.
  계정별 진행 요청은 1개, Codex 실행은 전체 1개다. 산출물 형식 검사를 통과하면 자동으로 Runner 검증을 시작한다.
  사람의 의미 검토를 통과했다고 기록하지 않는다. 고정 템플릿의 입출력 규칙을 기준으로 검증한다. reference/oracle/generator/validator, 작은 영역 전수(수열은 길이 1~3의 -1/0/1, 괄호는 길이 1~4의 모든 조합),
  경계·오버플로 또는 깊은 중첩·역순·미완성 괄호, 유효/무효 입력, 유형별 실제 오답 mutant 2개, 수정에 사용하지 않은 seed의 최종 대조를 수행한다.
- 검증 실행 15개는 기존 Runner 큐·격리·lease·결과 해시 검증을 재사용한다. 사용자 작업을 우선하며,
  이미 실행 중인 한 검증 작업을 강제로 중단하지는 않는다. 전부 통과한 뒤에만 `ready=true`로 본인의 문제 목록에 추가한다.
- 검증 중 문제·private 산출물은 일반 목록에 나타나지 않는다. 생성 검증 제출은 개인 제출/직접 실행 목록에서 제외한다.
  생성 작업과 CLI 사용량은 DB에 보존한다. ChatGPT 사용량에 임의의 API 비용을 부여하지 않는다.
- 원격 생성 결과의 재전송은 저장 결과를 사용한다. 실행 중 워커 중단은 자동 재호출하지 않고 운영 확인 상태로 남긴다.

`ocr-serv`에서는 설치된 standalone Codex CLI와 ChatGPT 로그인을 사용해 전용 컨테이너를 설치한다.
비-root UID, 읽기 전용 루트, capability 제거, CPU/메모리/PID 제한과 별도 `gamjaoj_generation` 네트워크를 적용한다.
인증 파일만 전용 경로로 복사하며 홈 디렉터리·Docker 소켓·DB·GPT API 키는 마운트하거나 전달하지 않는다.
재설치 시 전용 인증 사본을 유지한다. 해당 인증이 만료되면 운영자가 갱신해야 한다.
별도 VM 설치도 지원하며, 이 경우 비-root·비-Docker 계정에서
`CODEX_HOME=$HOME/gamjaoj-generation/auth codex login --device-auth`를 먼저 수행한다.

```sh
./scripts/install-generation-worker.sh ocr-serv
# 별도 VM: ./scripts/install-generation-worker.sh GENERATION_SSH_ALIAS
```

생성 워커는 Java를 실행하지 않는다. 작성 프로그램의 실행은 모델 인증이 없는 `runner-serv`가 담당한다.
현재 생성은 검토된 템플릿의 변형이며, 임의 알고리즘의 자유 생성이나 완전 신규 문제 검증을 지원한다고 보지 않는다.

### 반복 소재 방지

- 새 요청은 테마 준비 → Codex 작성 → Runner 검증 순서다. 기본 분석 모델(Luna/low)로 구체적인 소재와 상황만 만들고 저장한다.
- 최근 8번 사용한 소재 분야를 피해서 선택하고, 같은 사용자의 최근 12개 제목·본문을 참고한다.
  창고/재고/물류/보관소/입출고/상자/공장 계열은 새 이야기에서 제외한다.
- 테마와 최근 이야기는 author에게만 전달한다. 독립 oracle에는 개인 분석·기존 이야기·테마를 전달하지 않는다.
- 제목 정규화 일치 또는 본문 문자 3-gram Dice 유사도 0.65 이상, 금지 소재 표현은 중복으로 처리한다.
  제목/본문만 한 번 수정하며 기존 reference/generator/validator/oracle/힌트/해설은 보존한다.
  수정 한도는 코드 검증 실패와 같은 문제당 1회를 공유한다. 의미 수준의 모든 유사성을 완벽히 검출하는 기능은 아니다.
- 테마도 기존 전체 월 API 예산 $10, 실제 사용액+미정산 예약, 사용량 불명 예약 보존 정책을 따른다.
  API 비활성/예산 부족이면 화면에 대기 이유를 표시한다. 다른 모델/과금 경로로 자동 전환하지 않는다.
  테마 호출 실패 시 명시적으로 재요청하거나 새 문제를 요청할 수 있다. 재요청은 추가 비용이 발생하며 최대 3회 시도다.
- 같은 생성 요청 키는 같은 테마 작업을 재사용한다. 기본 힌트나 기존 문제 풀이는 API 예산과 관계없이 유지된다.

### Runner 연결과 현재 공개 범위

웹·DB는 `ocr-serv` (`192.0.2.10`), 상시 채점 워커는 별도 `runner-serv` (`192.168.0.212`)에서 운영한다.
일반 계정의 실행은 `SUBMISSIONS_ENABLED=true`로 활성화한다. 초대코드로 가입한 사용자가 제출하면
전용 VM의 워커가 작업을 가져가 격리 채점한다. Runner가 일시 중단되면 제출은 DB에 대기한다.
운영상 새 제출을 닫으려면 앱 서버 `.env`를 `SUBMISSIONS_ENABLED=false`로 바꾸고 앱을 재생성한다.
이 설정은 기존 대기 작업을 취소하거나 실행 중인 채점을 중지하지 않는다.

별도 Runner VM은 `runner-serv` (`192.168.0.212`)이며 다음 설치 경로를 사용한다.
해당 VM에는 Python 3.9+, Docker와 전용 계정의 systemd user lingering 설정이 필요하다.

```sh
./scripts/install-worker.sh runner-serv
```

Runner에는 웹 DB 비밀번호나 운영 DB 접근 권한을 주지 않는다. 제한된 worker API 토큰과
API 주소만 전달하며, 제출 Sandbox에는 그 토큰도 전달하지 않는다.
전용 VM의 실제 격리·재부팅 자동 시작·실행 중 워커 장애 복구를 확인한 뒤 일반 제출을 활성화했다.
`WORKER_TOKEN`은 웹 배포 시 무작위 생성하며 일반 로그인 쿠키로 worker API를 호출할 수 없다.
워커 서비스는 `gamjaoj-worker.service`, 상태는 `~/gamjaoj-worker/state/`에 저장한다.
Docker 그룹 권한을 새로 추가했다면 systemd user manager에도 반영해야 한다.
설치 스크립트는 SSH 셸뿐 아니라 사용자 서비스의 Docker 접근도 검사한다.
전용 VM 재부팅으로 권한을 반영할 수 있으며 lingering과 서비스 enable로 부팅 후 자동 시작한다.

```sh
ssh runner-serv 'systemctl --user status gamjaoj-worker --no-pager'
ssh runner-serv 'journalctl --user -u gamjaoj-worker -n 50 --no-pager'
python3 scripts/smoke-dedicated-runner.py
```

전용 Runner 검증은 임시 계정으로 AC/WA/CE/TLE를 제출하고 실제 워커 ID·단일 완료·원본 보존을 확인한다.
일반 제출 공개 상태에서는 execution_grant 없이 검증한다. `--restart-worker`는 공개 전의 빈 큐에서만
실행 중 워커를 강제 종료하여 자동 복구를 검사한다. 검증 계정과 DB 기록은 종료 시 정리한다.

서버 검증 `scripts/smoke-submissions.py`는 실제 HTTP/PostgreSQL/Docker 경로에서
앱 재시작, 실행 중 워커 강제 종료, 응답 유실 뒤 결과 재전송, 오래된 attempt의 결과 거부를 확인한다.
이는 초기 공유 VM 검증용이며 전용 워커가 없는 닫힌 검증 환경에서만 사용한다.
일반 제출을 공개한 뒤에는 배포 스크립트가 공유 VM 실행 검증을 건너뛰며,
스모크 자체도 공유 VM 실행을 차단한다. 이후 실행 검증은 별도 Runner VM에서 수행한다.

인증 계약 근거: [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html),
[로그아웃](https://docs.spring.io/spring-security/reference/servlet/authentication/logout.html),
[JDBC 세션](https://docs.spring.io/spring-session/reference/configuration/jdbc.html).

운영 채점은 전용 Runner VM에서만 수행한다. 앱 서버에서 수동 워커를 추가 실행하지 않는다.

### Supported submission languages

Ordinary, shared, generated, training and diagnostic problems support Java 8, C++17 (GCC 13), and
CPython 3.12. Select the language above the editor; drafts are separate per user/problem/language.
Imports/downloads use Main.java, Main.cpp or Main.py. Diagnostic attempt counts are shared across
languages. References and generators remain Java; their validated input/output packages work with
all three learner languages.

| Language | Wall time per test, including startup | Container memory |
| --- | --- | --- |
| Java 8 | 5 s | 384 MiB (128 MiB heap) |
| C++17 | 3 s | 256 MiB |
| Python 3.12 | 8 s | 256 MiB |

Compilation has a separate 30 s limit; C++ compilation gets 512 MiB. Profiles and digest-pinned
images live in `runner/languages.json`. These initial language-wide limits are stored with each
submission and verified by the worker/server. Legacy records retain their original runtime.
See [execution policy](docs/GamjaOJ_Multilanguage_Execution.md) for boundaries and verification.

```sh
docker pull "$(cat runner/cpp-image.txt)"
docker pull "$(cat runner/python-image.txt)"
GAMJAOJ_DOCKER_TESTS=1 python3 -m unittest tests.test_languages -v
python3 scripts/smoke-diagnostic-pilot.py --reassess --language CPP
python3 scripts/smoke-shared-catalog.py --language PYTHON
```

### Java 8 런타임

Java submissions and custom runs use the digest-pinned Temurin 8u502 image(`runner/java-image.txt`).
Java 9 이후 문법/API는 사용할 수 없다. 서버 애플리케이션의 Java 버전과 제출 런타임은 별도다.
`runner/java21-image.txt`는 기존 제출의 미완료 처리·재전송 호환을 위한 고정 이미지다.
기존 문제/코드/결과를 덮어쓰지 않고 V5에서 당시 런타임을 제출 행에 보존한다.

## M0 실행·검증

Python 3.9 이상과 로컬 Docker Engine이 필요하다. Runner는 Docker와 같은 호스트에서
실행한다. 원격 Docker context를 사용하지 않는다. Java는 digest로 고정한 컨테이너에서 실행한다.

```sh
docker pull "$(cat runner/java-image.txt)"
python3 -m unittest -v tests.test_judge
GAMJAOJ_DOCKER_TESTS=1 python3 -m unittest -v tests.test_judge
python3 -m runner.judge examples/Main.java
```

실제 격리 검증에는 `GAMJAOJ_DOCKER_TESTS=1`이 필요하다. 기본 테스트 실행은 Docker 검증을 건너뛴다.
운영자용 결과·제출 스냅샷·문제 스냅샷은 `.state/runs/<run_id>/`에 저장한다.
문제 JSON과 내부 오류 로그는 비공개 운영 자료이며 향후 일반 문제 API에 그대로 노출하지 않는다.

현재 Runner 정책 `java8-judge-v1` (기존 Java 21 작업의 `java21-m0-v2`도 보존):

- 사용자 제출·직접 실행과 자원 검사는 호스트 배타 잠금으로 하나씩 실행한다.
  서버가 `FUNCTIONAL`로 저장한 독립 생성 검증만 공유 잠금과 두 개의 호스트 슬롯으로 최대2개 실행한다.
- 컴파일과 각 테스트는 새 컨테이너로 실행한다. 컴파일에 annotation processor를 사용하지 않는다.
- CPU 0.5개, 메모리·swap 합계 384 MiB, JVM heap 128 MiB, PID 128개로 제한한다.
- 비특권 UID, capability 제거, 기본 seccomp, 읽기 전용 루트, 네트워크 차단을 적용한다.
- 제출 소스 64 KiB, 실행 stdout·stderr 합계 64 KiB, 컴파일 산출물 8 MiB로 제한한다.
- wall-clock 한도는 컴파일 30초·테스트 5초이며 Docker 시작·연결 시간을 포함한다.
  이는 알고리즘 CPU 측정값이 아니다. 컨테이너 내부에도 35초·8초 종료 제한을 둔다.
- 정답은 호스트 비교기만 읽으며 ASCII 공백 단위의 바이트 토큰을 정확히 비교한다.
- 문제 패키지 해시는 키 정렬·UTF-8·공백 없는 JSON을 기준으로 고정한다.
  v2는 이 해시 계약과 worker attempt label을 추가하며 기존 v1 결과를 덮어쓰지 않는다.
- 출력 제한 → 시간 제한 → 커널 OOM → 종료 코드 → 출력 비교 순서로 판정한다.
  커널 OOM 증거가 있을 때만 MLE이며 Java heap 고갈만으로 MLE라고 단정하지 않는다.
  원인 불명 SIGKILL/SIGTERM은 IE다. 컴파일 단계의 제한 초과는 CE와 상세 사유로 기록한다.

## 서버 설치

```sh
./scripts/deploy-m0.sh
ssh ocr-serv 'cd ~/gamjaoj/current && python3 -m runner.judge examples/Main.java'
```

설치 스크립트는 허용한 소스만 별도 release 디렉터리에 전송하고 서버 검증 성공 후
`~/gamjaoj/current`를 전환한다. 기존 서비스에 대한 stop/restart/prune은 수행하지 않는다.
M0는 운영자 CLI 설치이며 채점 Sandbox는 서비스 네트워크에 연결하지 않는다.
웹 계정 기능의 설치는 위의 `deploy-web.sh`로 별도 관리한다.

격리 옵션의 근거: [Docker 실행 계약](https://docs.docker.com/engine/containers/run/),
[Docker 보안 모델](https://docs.docker.com/engine/security/).

개인 생성 문제는 소유자만 목록·풀이·직접 실행·훈련·힌트에 접근할 수 있다. 정답과 해설을 읽고 승인할 필요 없이 검증 후 풀이로 연결한다.

선택한 유형과 같은 유형의 완료된 본인 풀이 분석을 `내 문제 생성 → 반영할 풀이 분석`에서 선택하면 저장된 학습 제안을 Codex의 힌트·해설 작성에 반영한다. 분석 API를 다시 호출하지 않으며, 원본 코드와 개인 분석을 독립 oracle에 전달하지 않는다. API 활성화에 운영자 아이디는 필요하지 않다.

생성 재시도는 oracle / 생성기 / 입력 검증기를 각각 분리한다. reference 수정 시에는 일관성을 위해 힌트·해설도 함께 수정한다. 수정 후 모든 Runner 검증은 다시 수행하며, Runner 인프라 오류나 신뢰된 mutant의 이상은 모델 재생성으로 처리하지 않는다. 재사용한 산출물과 실제 Codex 호출 시간은 usage에 기록한다.

### 자유 출제 문제 검토 보류

`내 문제 생성 → 직접 요청하기 → 게시한 문제 → 문제 오류 신고·풀이 보류`에서
본인의 자유 출제 문제를 사유와 함께 보류할 수 있다. 다른 계정이나 공용 문제는 이 경로로 변경할 수 없다.
보류한 문제는 문제 목록에서 제외하고 새 실행·제출·훈련·분석·분석 재시도를 차단한다.
기존 초안·제출·실행·훈련·판정은 보존하며 검토 중 상태를 표시한다. 기록을 읽을 수 있도록
`/api/problems`와 편집기의 문제 선택에는 `problemHeld` 상태와 함께 남기되 제출은 비활성화한다.
이전 분석은 학습 근거 선택·새 출제 입력에서 제외한다. 호출 전 대기 분석은 건너뛰고,
이미 RUNNING으로 예약된 호출은 결과·실제 비용·미상 사용량을 기존 정책대로 정산한다.
보류 전에 접수된 채점과 동일 요청의 재확인은 유지한다. 자동 재게시·수정·재채점은 제공하지 않는다.
`python3 scripts/smoke-problem-review.py`는 합성 계정과 게시 fixture로 운영 PostgreSQL/API 경로를 검사하며
모델/Runner 호출 및 출제 품질 검증을 수행하지 않는다. 검사 후 합성 데이터를 정리한다.

### 분석에서 다음 훈련으로 이어가기

완료된 `풀이 분석 → 이 분석으로 다음 훈련 준비`에서 연습할 지점과 목표를 확인한다.
확인한 목표는 서버에 저장되고 `훈련 기록 → 확인한 목표로 다음 훈련`에 나타난다.
추천은 같은 검증된 풀이 유형과 선택한 목표의 본인/공용 게시 문제 중 원래 문제 및 이미 AC인 문제를 제외한 후보이다.
문제 내용을 확인한 뒤 시작하면 기존 훈련 세션과 연결된다. 자유 출제는 태그 유사성으로 규칙을 추정하지 않으며,
해당 목표로 직접 생성하고 검증·게시한 문제만 후보로 사용한다.
준비된 문제가 없으면 `이 목표로 새 문제 요청`으로 기존 Codex 출제·독립 검토·Runner 검증 경로를 이용한다.
확인/추천/재확인 자체는 모델을 호출하지 않는다. 출제 버튼은 기존 테마 API/출제 정책을 따르는 명시적 생성 요청이다.
훈련을 종료하고 마지막 정식 제출이 AC이면 도움 사용 여부를 확인한다. `도움 없이 정답 해결`은 **본인 확인**이며,
외부 도움 사용을 자동 검증하거나 정답 한 번을 숙련도 점수로 바꾸지 않는다. 직접 실행 성공·이전 AC 뒤 마지막 WA는 통과로 인정하지 않는다.
원래 문제나 훈련 문제가 검토 보류되면 이 연결도 학습 근거에서 보류된다. 기존 판정은 변경하지 않는다.
훈련을 마친 뒤 `같은 목표로 다시 연습`을 누르면 목표를 유지한 채 다음 시도를 시작한다.
이전 시도의 판정·도움 사용 확인·훈련 기록은 `이전 시도`에 접어 보관한다. 이미 AC인 문제는 다음 후보에서 제외한다.
재연습 버튼 자체는 모델을 호출하지 않는다. 응답 유실 재요청은 같은 시도를 반환하고, 이전 탭의 시작·생성·확인은 시도 번호가 다르면 거부한다.
`python3 scripts/smoke-practice-followup.py`는 합성 분석/문제 후보에서 시작해 실제 Java8 Runner AC 및 재확인 저장을 검사한다.
원본 분석의 모델 품질이나 새 문제 생성 품질을 검증하는 테스트는 아니며, 합성 데이터는 종료 시 정리한다.

### Runner 성능 진단

Runner는 판정 `result.json`과 별개로 같은 디렉터리에 `performance.json`을 기록한다.
코드/계획 해시, 테스트 개수, 캐시 적중, 호스트 잠금·컴파일·컨테이너 제어·실행 대기·정리 구간을 확인할 수 있다.
worker journal의 `performance` JSON은 submission ID/attempt token으로 연결되며 전달 재시도의 확정 여부도 기록한다.
상시 worker는 `GAMJAOJ_DOCKER_CONTROL=engine`으로 정확한 Sandbox 이름의 상태 조회·강제 종료·삭제를
로컬 Unix socket의 Docker Engine API v1.45로 전달한다. 생성과 start/attach 및 판정의 시간 제한은 기존 CLI 경로를 사용한다.
원격 TCP endpoint와 호환되지 않는 API 버전은 거부하며, 설치 시 서비스 계정으로 API 호환성을 먼저 검사한다.
실패한 API 요청을 다른 경로로 자동 재시도하지 않는다. `cli` 모드는 기존 제어 방식의 비교·복구용이다.
CLI 직접 실행 기본값은 `cli`이고 설치된 서비스에서 `engine`을 명시한다. 진단의 `dockerControl`로 구분한다.
상위 `compile_total`/`test_total`과 하위 구간을 중복 합산하지 않는다. Docker 실행 생명주기는 순수 Java CPU 시간이 아니다.
큐 시간은 기존 judge_job.created_at와 judge_attempt.started_at을 조회한다. claim HTTP 시간으로 대체하지 않는다.
`PYTHONPATH=. python3 scripts/profile-runner.py --help`로 저장 검증 묶음의 오프라인 진단 도구 사용법을 확인한다.
`--control cli|engine`으로 동일 자료의 제어 방식을 비교하고, `--disable-cache`로 컴파일 캐시 없는 경우를 측정한다.
오프라인 도구는 운영 큐·coordinator·전달 시간을 포함하지 않는다. 상시 worker의 설정이나 게시 상태를 변경하지 않는다.

### 제한적 Runner 병렬 실행

서버는 새 `judge_job.execution_mode`를 저장하고 assignment와 결과의 실행 모드를 대조한다.
기존 작업·일반 제출·직접 실행·알 수 없는 검증은 `EXCLUSIVE`가 기본이다. 태그 검증은
sample/small 입력만 들어 있는 reference/oracle/validator 묶음에 한해 `FUNCTIONAL`로 등록한다.
경계·최대 입력이 하나라도 섞인 묶음은 독점 실행한다. 자유 출제는 예제/생성 입력의 reference·oracle과
최종 small/seed 대조만 병렬 대상이며, stress·최종 package 자원 검사는 독점 실행한다.

상시 worker는 `--slots 2`로 실행하며 기존 `state/identity.json`과 새 `state/slots/1/identity.json`의
서로 다른 ID를 유지한다. 슬롯마다 assignment·attempt·pending report와 heartbeat를 분리하고
컴파일 캐시는 잠금으로 보호해 공유한다. 재시작 시 두 슬롯의 저장 결과를 재전송하고 자기 attempt만 정리한다.
두 슬롯 기록이 있는 state를 `--slots 1`로 실행하는 것은 복구 기록을 놓치지 않도록 거부한다.

서버의 claim 잠금 아래 전체 유효 assignment를 최대2개로 제한한다. 하나라도 독점 작업이면
추가 claim을 막고, 큐 맨 앞의 사용자/독점 작업이 기다리면 뒤의 생성 검증으로 빈 슬롯을 채우지 않는다.
이미 시작한 작업은 선점하지 않는다. lease가 만료된 이전 실행이 남아 있어도 호스트 잠금이
독점 실행과 기능 검증의 겹침 및 기능 검증3개 동시 실행을 막는다. 늦은 결과는 기존 token fence로 거부한다.

호환 배포 순서는 새 Runner → 앱(V23)이다. 이전 앱은 새 worker에 모드를 보내지 않아 독점 실행한다.
두 슬롯의 미완료·미전달 작업을 확인하지 않고 예전 단일 슬롯 worker로 되돌리지 않는다.
`scripts/smoke-runner-scheduling.py`는 합성 작업을 실제 큐에서 실행하여2개 겹침·사용자 우선·독점 검사 순서를
확인한다. 모델 생성 품질이나 실제 생성 admission을 검증하는 도구는 아니다.
오프라인 비교 도구의 `--slots 2`는 private input에서 명시한 `executionMode`만 사용하며,
비교에는 job 시간 합이 아니라 출력 JSON의 `elapsedMs`를 사용한다.

### Runner 빌드·실행 제한 계약

`runner/execution-profile.json`은 실제 Sandbox 옵션·Java/컴파일 명령·시간/출력 제한의 입력이다.
`scripts/build-web.sh`는 먼저 `scripts/export-runner-contract.py`를 실행해 Runner Python 소스 전체와
두 승인 이미지 파일의 해시 및 profile을 서버 리소스에 포함한다. Runner 변경 후 Maven을 직접 실행할
때에도 export를 먼저 실행한다. source/profile만 바꾸고 앱 또는 Runner 한쪽만 배포하면 서로 호환되지 않는다.

신규 claim은 V24 `judge_attempt.execution_environment_json`에 기대 계약을 저장하고 assignment로 전달한다.
Runner는 heartbeat 전에 새 실행 환경을 대조한다. 다르면 Sandbox 실행/lease 연장을 하지 않으며 기존 만료 정책을 따른다.
이미 저장된 결과는 재실행하지 않고 원래 attempt의 계약으로 재전송한다. 서버도 현재 빌드가 아니라
저장된 attempt와 `runner_environment.contract`를 비교한다. transport(cli/engine)는 결과에 별도로 기록하고
검증 근거 환경 해시에 포함한다. 필드 누락/빌드·제한 불일치는 신규 attempt 결과로 수락하지 않는다.

기존 RUNNING attempt의 NULL 계약은 호환 복구만 허용하며 환경 검증 근거로 인정하지 않는다.
재시도에서 새 token을 발급할 때는 새 계약을 저장한다. 최초 V24 전환은 큐가 빈 상태에서 새 Runner → 앱 순서로 한다.
이후 빌드 변경도 기존 시도의 실행을 비운 뒤 앱/Runner 계약을 함께 맞춰 배포하고 불일치를 확인한다.
저장된 report에 새 환경을 소급해서 덧붙이지 않는다.

이 기록은 인증된 전용 worker의 빌드·설정 선언과 서버 대조다. 하드웨어 서명이나 호스트 커널/Docker 버전,
상위 cgroup의 실제 할당량 증명은 아니다. 현재 고정 Runner VM 밖의 다른 호스트까지 동일 실행 환경으로
인정할 근거는 제공하지 않으며, 전체 게시 검증을 계속 수행한다.
