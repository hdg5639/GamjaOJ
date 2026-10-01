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
사용자는 OAuth 페이지 선택기에서 공유할 상위 페이지를 선택한 뒤, GamjaOJ에서 저장 위치로 지정한다.
Database 행 대신 **일반 페이지 아래의 하위 페이지**를 만든다.

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
- 사용자 소유 코드·문제 링크·제목·언어·통과 시각만 저장. 문제 본문/해설/숨은 테스트 제외.
- 코드 실행, 진단평가, 출제 내부 검증, 검토 보류 문제는 제외.
- GitHub: `<폴더>/<사용자>/<문제 버전>/<언어>/<소스 파일>` 및 `README.md`.
  두 파일은 개별 Contents API 커밋이다. 중간 실패 시 하나만 먼저 반영될 수 있고 재시도가 나머지를 처리한다.
  같은 내용은 다시 커밋하지 않으며 기존 파일은 SHA 조건으로 갱신한다.
- Notion: 문제·언어별 페이지에 관리용 정보/코드 블록과 사용자 회고 영역.
  이후에는 관리용 두 블록만 갱신한다. 관리 블록을 삭제하면 오류를 표시한다.
- 같은 목적지·문제·언어에는 최신 제출 시각의 AC가 우선. 과거 제출 수동 저장으로 덮어쓰지 않는다.
- 동일 계정 재인증은 저장 위치와 Notion page/block ID를 보존. 연결 해제 후 새 연결/다른 계정 연결은 별개이며
  이전 외부 자료는 남고 이후 저장은 새 기록을 만들 수 있다.
- worker lease 60초, 요청 timeout 15초. 재시작 시 만료 lease 회수. 최대 6회 자동 시도, 지수 backoff.
- Notion 생성 응답 유실은 상위 페이지에서 고유 표시를 찾아 복구한다. 찾지 못하면 `DELIVERY_UNCERTAIN`으로
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
