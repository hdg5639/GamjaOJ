# GitHub / Notion 풀이 자동 저장

운영자가 앱을 등록하고 아래 환경값을 설정해야 사용자 연결이 열린다. 기본값은 꺼짐이다.
이 문서는 앱 등록/운영 절차이며, 실제 provider 검증 완료를 의미하지 않는다.

## GitHub App

GitHub App을 생성한다. 여러 회원이 자기 저장소에 설치할 수 있도록 공개 설치를 허용한다.
Repository permissions는 **Contents: Read and write**, **Metadata: Read**만 요청한다.
사용자는 설치 시 **Only select repositories**로 풀이 저장소만 선택하는 것을 권장한다.
Webhook은 이 구현에서 사용하지 않는다. Webhook 수신 주소나 App private key는 필요 없다.

- Callback URL: `https://서비스도메인/api/integrations/github/callback`
- 사용자 OAuth 인증 활성화, expiring user access tokens 사용.
- Client ID, Client secret, App slug를 서버 환경값에 설정.
- 사용자 흐름: 내 설정 → 풀이 자동 저장 → App 설치 / 저장소 선택 → GitHub 연결 → 저장소·브랜치·폴더 선택 → 자동 저장 켜기.

서버는 GitHub App **user access token**을 사용한다. 앱 설치 범위와 사용자의 권한을 모두 만족하는
저장소만 쓸 수 있다. PKCE S256과 사용자 세션에 묶인 10분짜리 일회용 OAuth state를 사용한다.
만료 전에 refresh token을 교환하며, 교환 결과가 불명확하면 재연결을 요구한다.
브랜치 보호 규칙은 우회하지 않는다. 직접 push가 허용되는 전용 저장소/브랜치를 선택한다.

참고: https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app

## Notion public integration

다른 회원의 workspace도 연결할 수 있는 public OAuth integration을 등록한다.
콘텐츠 Read / Insert / Update 권한이 필요하다. 사용자 정보/이메일 접근은 필요하지 않다.
Redirect URI: `https://서비스도메인/api/integrations/notion/callback`.
Notion의 공개 integration 배포/심사 조건은 운영자가 등록 시 확인한다.
사용자는 OAuth 페이지 선택기에서 공유할 상위 페이지/데이터베이스를 허용한 뒤 GamjaOJ에서 저장 위치를 지정한다.
- **페이지 선택:** 첫 저장 때 그 아래에 `GamjaOJ 풀이` 인라인 데이터베이스를 자동 생성한다.
- **표 선택:** 공유받은 기존 data source에 저장하며 필요한 관리 열만 추가한다.
- 표 열: 문제(기존 제목 열), 언어(select), 결과(select, AC), 문제 링크(url), 통과 시각(date),
  실행 시간 (ms)(number), 최대 메모리 (MiB)(number),
  GamjaOJ ID(rich_text, 복구용 고유 표시). 같은 이름의 열이 다른 유형이면 오류로 안내하고 변환하지 않는다.
- 행을 열면 코드와 내 회고가 보인다. 같은 사용자·문제·언어는 기존 행/코드 블록을 갱신한다.
- 이전 버전에서 저장 위치를 설정한 계정은 기존 개별 하위 페이지 방식을 유지한다.
  새 표 방식을 쓰려면 목록에서 위치를 선택한 뒤 저장 설정을 다시 적용한다. 기존 페이지는 이동/삭제하지 않는다.

참고: https://developers.notion.com/guides/get-started/authorization

## 서버 설정

비밀값은 Git에 추가하지 않고 운영 서버 `~/gamjaoj/web/.env` (권한 600)에 설정한다.
`EXPORT_TOKEN_KEY`는 `openssl rand -base64 32`로 생성하는 32-byte AES 키다.
토큰은 사용자·provider에 결합된 AES-256-GCM으로 DB에 암호화한다.
키를 잃거나 교체하면 기존 연결은 복호화할 수 없으므로 키도 안전하게 백업한다.
이 구현에는 기존 토큰의 키 회전 마이그레이션이 없다.

```dotenv
PUBLIC_BASE_URL=https://서비스도메인
EXPORTS_ENABLED=true
EXPORT_TOKEN_KEY=<32-byte base64 key>
EXPORT_GITHUB_CLIENT_ID=<GitHub App client id>
EXPORT_GITHUB_CLIENT_SECRET=<GitHub App client secret>
EXPORT_GITHUB_APP_SLUG=<app slug>
EXPORT_NOTION_CLIENT_ID=<Notion OAuth client id>
EXPORT_NOTION_CLIENT_SECRET=<Notion OAuth client secret>
```

한 provider만 설정해도 해당 연결만 열린다. 설정 후 승인된 web 배포 경로로 반영한다.
`EXPORTS_ENABLED=false`는 새 자동 등록과 worker 실행을 중단한다. 진행 중인 HTTP 요청은 이미
외부 서비스에서 처리됐을 수 있다. 사용자 연결 해제는 이후 요청과 재시도를 차단하지만,
이미 생성된 외부 파일/페이지를 삭제하지 않는다. 외부 서비스에서도 앱 권한을 철회할 수 있다.

## 저장 및 복구 계약

- 정식 AC 판정과 같은 DB 트랜잭션에서 durable queue에 등록. 외부 HTTP는 별도 worker에서 수행.
- 사용자 소유 코드·문제 링크·제목·언어·통과/제출 시각·공개 난이도/분류·테스트별 최대 wall time 및 관측 메모리만 저장. 문제 본문/해설/숨은 테스트 제외.
- 코드 실행, 진단평가, 출제 내부 검증, 검토 보류 문제는 제외.
- GitHub 신규 설정 기본은 `problem-v1`: `<폴더>/<난이도>/<문제 버전>. <제목>/<소스 파일>` 및 `README.md`.
  예: `GamjaOJ/Easy/sum-v1. 두 정수의 합/Main.java`. 언어별 파일을 같은 문제 폴더에 저장한다.
  README는 실행 시간·메모리·분류·난이도 근거·제출 일자를 포함한다. 커밋 메시지에도 Time / Memory를 기록한다.
  메모리는 Runner 호스트의 cgroup 커널 peak counter를 관측한 값이며 런타임·파일 캐시를 포함한다.
  실행된 테스트별 관측값의 최댓값을 MiB(1,048,576 bytes)로 표시한다. 2ms 관측 사이 컨테이너가 종료되면
  마지막 할당을 놓칠 수 있다. 기존 기록/미지원 호스트의 누락값은 미측정이며 0으로 만들지 않는다.
  폴더는 delivery checkpoint에 저장하여 같은 delivery의 재제출·재시작 때 재사용한다.
  기존 설정/layout 미설정은 `legacy`: `<폴더>/<사용자>/<문제 버전>/<언어>/<소스 파일>`.
  설정의 정리 방식 선택 후 적용하면 새 목적지 identity로 전환한다. 기존 외부 파일은 이동/삭제하지 않는다.
  새 방식은 소유 표시가 있는 README를 먼저 저장한다. 다른 기록/표시 없는 폴더는 PATH_CONFLICT로 중단한다.
  두 파일은 개별 Contents API 커밋이다. 중간 실패 시 하나만 먼저 반영될 수 있고 재시도가 나머지를 처리한다.
  같은 내용은 다시 커밋하지 않으며 기존 파일은 SHA 조건으로 갱신한다.
- Notion: 문제·언어별 표 행(또는 이전 설정의 개별 페이지)에 관리용 정보/코드 블록과 사용자 회고 영역.
  표에는 실행 시간·메모리 숫자 열을 추가하고 관리용 정보에도 같은 값을 기록한다. 미측정 숫자는 null이다.
  이후에는 관리용 두 블록만 갱신한다. 관리 블록을 삭제하면 오류를 표시한다.
- 이미 목적지를 설정한 계정은 GitHub/Notion별 자동 업로드 스위치로 즉시 켜고 끈다.
  이후 AC 자동 등록에 적용하며 이미 등록된 작업과 수동 저장은 유지한다. 목적지/경로 설정은 바꾸지 않는다.
- 같은 목적지·문제·언어에는 최신 제출 시각의 AC가 우선. 과거 제출 수동 저장으로 덮어쓰지 않는다.
- 동일 계정 재인증은 저장 위치와 Notion page/block ID를 보존. 연결 해제 후 새 연결/다른 계정 연결은 별개이며
  이전 외부 자료는 남고 이후 저장은 새 기록을 만들 수 있다.
- worker lease 60초, 요청 timeout 15초. 재시작 시 만료 lease 회수. 최대 6회 자동 시도, 지수 backoff.
- 페이지 아래 자동 표는 V64 notion_export_table의 사용자·상위 페이지별 공유 체크포인트와 60초 lease로
  생성 상태를 저장한다. 여러 풀이/프로세스/재시작이 같은 상태를 사용한다. 표의 관리 설명을 확인해 재사용한다.
- 표 행 생성 응답 유실은 data source query의 GamjaOJ ID 필터로 찾고, 기존 개별 페이지는 상위 페이지에서 고유 표시를 찾아 복구한다. 찾지 못하면 `DELIVERY_UNCERTAIN`으로
  멈추고 새 페이지를 무작정 만들지 않는다. 페이지 제목/관리 블록을 변경하면 자동 복구가 어려울 수 있다.
- 연결/목적지 변경은 다음 외부 호출을 fence로 차단한다. 이미 전송된 네트워크 요청을 취소하거나
  외부 서비스와 DB 사이에 분산 트랜잭션을 보장하지는 않는다.

## 등록 후 실제 검증

테스트 계정과 전용 저장소/페이지로 수행한다.

1. 계정 연결, 공유하지 않은 저장소/페이지 미노출, 로그인 사용자와 OAuth state 결합 확인.
2. Java/C++/Python 정식 AC 후 코드·링크 저장, 같은 문제 재제출 시 갱신, Notion 회고 유지.
3. 제출 기록에서 과거 AC 수동 저장. 진단평가/타인 제출/코드 실행 거부 확인.
4. 권한 철회 → 실패 표시 → 권한 복구/재연결 → 재시도.
5. 전송 중 서버 재시작 후 queue 복구와 GitHub 중복 커밋/Notion 중복 페이지 방지 확인.

자동 테스트는 HTTP provider 대역을 사용한다. OAuth 앱 등록과 실제 provider 쓰기까지 검증한 것으로 해석하지 않는다.
