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
문제 풀이·개인 제출 기록과 영속 채점 작업 큐를 연결했다. 학습 분석·복습 기록은 후속 단계다.

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
파일 도구는 접힌 메뉴로 제공한다. Tab은 4칸 들여쓰기, Ctrl/⌘+Enter는 제출이다.

로그인 후 수동 검증 문제 `sum-v1`의 본문·예제와 Java 코드 입력창을 볼 수 있다.
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
  배포 시 Java 8/기존 Java 21을 지원하는 worker를 먼저 설치한 뒤 앱을 배포한다.

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

### Java 8 런타임

신규 제출과 직접 실행은 Temurin 8u502의 digest 고정 이미지를 사용한다(`runner/java-image.txt`).
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

- 동일 운영 계정의 채점은 호스트 잠금으로 한 번에 하나씩 실행한다.
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
