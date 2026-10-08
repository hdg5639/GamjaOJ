ALTER TABLE app_user ADD COLUMN blocked BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE app_user ADD COLUMN admin_role VARCHAR(16) NOT NULL DEFAULT 'MEMBER' CHECK (admin_role IN ('MEMBER','ADMIN'));
ALTER TABLE app_user ADD COLUMN access_epoch INTEGER NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN admin_revision INTEGER NOT NULL DEFAULT 0;
CREATE TABLE admin_audit (
 id UUID PRIMARY KEY, actor VARCHAR(100) NOT NULL, action VARCHAR(80) NOT NULL,
 target VARCHAR(160) NOT NULL, reason VARCHAR(500) NOT NULL,
 before_json TEXT NOT NULL, after_json TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX admin_audit_recent ON admin_audit(created_at);
CREATE TABLE admin_site_setting (
 id INTEGER PRIMARY KEY CHECK (id=1), maintenance BOOLEAN NOT NULL DEFAULT false,
 message VARCHAR(300) NOT NULL DEFAULT '', revision INTEGER NOT NULL DEFAULT 0
);
INSERT INTO admin_site_setting(id) VALUES(1);
CREATE TABLE announcement (
 id VARCHAR(80) PRIMARY KEY, kind VARCHAR(16) NOT NULL CHECK (kind IN ('공지','새 기능','업데이트')),
 title VARCHAR(120) NOT NULL, summary VARCHAR(400) NOT NULL, body TEXT NOT NULL,
 pinned BOOLEAN NOT NULL DEFAULT false, published BOOLEAN NOT NULL DEFAULT false,
 published_at TIMESTAMP WITH TIME ZONE, revision INTEGER NOT NULL DEFAULT 1,
 created_by VARCHAR(100) NOT NULL, updated_by VARCHAR(100) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK (published=false OR published_at IS NOT NULL)
);
CREATE INDEX announcement_public ON announcement(published,pinned,published_at);
ALTER TABLE problem_version ADD COLUMN catalog_title VARCHAR(120);

INSERT INTO announcement(id,kind,title,summary,body,pinned,published,published_at,created_by,updated_by) VALUES('queue-guide-20261007','공지','제출·실행이 몰리면 잠시 대기할 수 있어요','작업은 대기열에 들어간 뒤 러너 슬롯이 비면 처리됩니다.','여러 사람이 동시에 코드를 실행하거나 제출하면 결과가 나오기까지 시간이 더 걸릴 수 있어요. 대기 중이라는 표시가 보이면 같은 코드를 반복해서 보내지 않고 잠시 기다려 주세요.

대기 시간은 코드의 실행 시간 제한과 별개예요. 차례가 오면 컴파일·실행을 진행하고 결과 화면이 갱신됩니다.

오류가 표시되거나 오랫동안 상태가 바뀌지 않으면 작업 상태를 확인한 뒤 운영자에게 알려주세요.',true,true,'2026-10-07T00:00:00+09:00','migration','migration');
INSERT INTO announcement(id,kind,title,summary,body,pinned,published,published_at,created_by,updated_by) VALUES('batch-run-20261007','새 기능','여러 입력을 한 번에 실행해요','한 번 컴파일한 코드로 여러 테스트 입력을 확인할 수 있어요.','실행 결과에서 테스트 입력을 추가해 여러 경우를 한 번에 확인할 수 있어요. 컴파일한 코드를 공유하고 입력마다 출력과 실행 결과를 나눠 보여줍니다.

여러 입력은 최대 4개씩 병렬로 실행되며, 서버가 바쁘면 대기할 수 있어요. 실행은 연습용 확인이고, 정답 판정은 정식 제출로 확인해 주세요.',false,true,'2026-10-07T00:00:00+09:00','migration','migration');
INSERT INTO announcement(id,kind,title,summary,body,pinned,published,published_at,created_by,updated_by) VALUES('cpu-time-20261007','업데이트','실행 시간 표시와 채점 방식을 정비했어요','CPU 시간 기준과 요청·테스트 병렬 처리로 채점 흐름을 개선했어요.','CPU 시간 기준을 적용하고, 여러 요청과 테스트를 제한된 슬롯 안에서 병렬로 처리하도록 개선했어요. 문제와 언어에 표시되는 제한을 기준으로 풀어 주세요.

서버의 대기열에 머문 시간과 코드가 실제 실행된 시간은 달라요. 이전 제출은 당시 실행 환경의 기록을 유지하므로 새 제출과 표시 기준이 다를 수 있어요.',false,true,'2026-10-07T00:00:00+09:00','migration','migration');
INSERT INTO announcement(id,kind,title,summary,body,pinned,published,published_at,created_by,updated_by) VALUES('editor-selection-20261007','업데이트','에디터에서 선택한 코드가 더 잘 보여요','현재 줄의 선택 영역 배경과 글자 대비를 보정했어요.','에디터에서 현재 줄의 텍스트를 선택할 때 선택 배경과 글자의 대비가 더 분명하게 보이도록 조정했어요. 기존 테마와 글꼴 설정은 그대로 사용할 수 있어요.',false,true,'2026-10-07T00:00:00+09:00','migration','migration');
ALTER TABLE problem_version ADD COLUMN admin_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE diagnostic_bank ADD COLUMN admin_enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE diagnostic_bank ADD COLUMN admin_revision INTEGER NOT NULL DEFAULT 0;
CREATE TABLE admin_course_setting (
 id VARCHAR(80) PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT true,
 revision INTEGER NOT NULL DEFAULT 0
);
INSERT INTO admin_course_setting(id) VALUES('first-steps');
INSERT INTO admin_course_setting(id) VALUES('exam-a-foundation');
INSERT INTO admin_course_setting(id) VALUES('exam-pro-foundation');
INSERT INTO admin_course_setting(id) VALUES('linear-structures');
INSERT INTO admin_course_setting(id) VALUES('search-mastery');
INSERT INTO admin_course_setting(id) VALUES('graph-mastery');
INSERT INTO admin_course_setting(id) VALUES('dp-mastery');
INSERT INTO admin_course_setting(id) VALUES('query-optimization');
ALTER TABLE app_user ADD COLUMN admin_verify_failures INTEGER NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN admin_verify_locked_until BIGINT NOT NULL DEFAULT 0;
ALTER TABLE admin_course_setting ADD COLUMN course_json TEXT;
