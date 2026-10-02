# 로컬 데모 E2E 수용 시험

`demoE2eTest`는 PostgreSQL 18, 실제 Spring Boot HTTP 서버, 역할별 stdio MCP 프로세스와 SDK 클라이언트, 기존 `Runner`·`WorkerApi`·`ProcessExecutor`, 실제 호스트 Zig CLI를 연결한다. 일반 `test`에서는 `demo-e2e` 태그를 제외하며 이 시험의 Node 프로세스를 시작하지 않는다.

시뮬레이션 경계: PoC 로컬 신원 모델을 사용하며 Auth0 JWT·JWKS·OBO는 없다. 각 역할을 `MULINO_LOCAL_ROLE` 환경 변수로 지정한 실제 stdio MCP 프로세스를 사용하고, 백엔드는 `X-Mulino-Local-Role` 헤더로 신원을 확인한다.

사업 모델 대신 `model.mjs` 자식 프로세스가 고정된 역할 판단을 수행한다. 모든 사업 조회·변경은 컴파일된 `mulino`를 호출하며 최종 응답은 실제 `item.completed`/`agent_message` 이벤트로 반환한다. 자식에게는 Run capability만 전달하며 인간 토큰, OBO/M2M secret, DB 자격 증명은 전달하지 않는다. DB 직접 접근은 Java 시험의 초기 fixture 및 사후 검증만 담당한다. 이 결과는 실제 외부 신원 제공자(#21·#22) 설정, 외부 채팅 클라이언트 로그인, Codex 모델 실행, DockerExecutor의 격리 수용 시험을 대신하지 않는다.

## 실행

Java 21, PostgreSQL 18의 **별도 빈 시험 DB**, Docker(jOOQ 생성), Node 22 이상, Zig 0.16.0, OpenSSL이 필요하다. 누락된 전제는 실패하며 skip하지 않는다. 비밀 값은 저장소에 기록하지 않는다.

```bash
# 저장소 루트
npm ci --prefix mcp-server
(cd agents/cli && zig build)
# 미리 생성한 별도 loopback 시험 DB만 허용한다.
cd backend
DB_URL=jdbc:postgresql://127.0.0.1:55438/mulino_demo_e2e \
DB_USERNAME=mulino_test DB_PASSWORD=test-only \
./gradlew demoE2eTest --no-daemon --max-workers=1
```

DB URL은 명시적 loopback 주소와 `mulino_demo_e2e` 이름이어야 한다. Flyway clean 직전에 schema가 정확히 `demo_e2e`인지 확인한다. 스키마 안의 데이터는 시험 시작 및 BLOCK/APPROVE 독립 시나리오 사이에 초기화된다. 활성 창고 Case 중복 방지 정책을 우회하지 않는다. 업무 날짜만 2026-09-05로 고정하며 JWT와 lease는 실제 시각을 쓴다. 납기 후 검증에서는 시험의 업무 시계만 2026-09-08로 진행한다.

## 검증 경로

1. 인간 MCP 세션의 ASK와 명시적 SKU/창고/목표일을 갖는 Case 생성.
2. 실제 worker claim → Orchestrator CLI 업무 배정/대기 → 공급망 CLI 계산/조회/DONE → 재개 Orchestrator 구매 배정/대기 → 구매 CLI 제안과 서버의 WAITING 영수증.
3. 새 MANAGER MCP 세션에서 동일 Case·계획·승인을 조회하고 16,500원 제안을 검증. OPERATOR의 결정은 거부된다.
4. BLOCK은 발주·후속 확인을 만들지 않는다. 취소된 구매 의존성으로 부모가 한 번 재개하면 모델 fixture는 인간의 방침 검토를 위해 ABORTED를 반환한다. 이후 반복 claim은 IDLE이며 같은 제안을 재발행하지 않는다.
5. APPROVE와 동일 키 재시도는 발주를 한 번만 생성한다. 재개 구매 역할은 CLI로 실제 발주를 조회하고 DONE, 부모는 서버 관리 후속 책임을 확인한 뒤 DONE.
6. Case는 WAITING이고 구매 업무는 DONE이며 후속 담당자는 ORCHESTRATOR다. 납기 이후 실제 worker claim은 새 모델 없이 단일 Attention을 만들고 새 MCP 세션에서 조회한다. 생산·재고 목표가 달성되었다고 선언하지 않는다.

정확한 SQL 사후 검증과 `DEMO_E2E_*_PASS` 기록은 `backend/build/reports/tests/demoE2eTest/`에 남는다.

## 프로세스 재시작과 변경 승인안

정상 승인 경로도 `prepare`(기존 승인 대기)와 `resume`(인간 결정·역할 재개)를 **별도 Node 프로세스**로 실행한다. 재시작마다 새 MCP 서버·SDK 세션·Runner·토큰 클라이언트를 만들며 Case·계획·승인 참조는 MCP `list_cases` SKU 검색과 `get_case`로 DB에서 복구한다. 이전 프로세스의 변수나 Run capability를 재사용하지 않는다. 추가로 승인 대기 직후 첫 번째 ordered 시험이 `@DirtiesContext(AFTER_METHOD)`로 실제 Spring application context·Tomcat HTTP listener·DB pool을 종료한다. 두 번째 시험은 같은 DB/schema를 초기화하지 않고 새 context를 시작하고 재시작한 HTTP listener가 보고한 실제 port로 다시 연결한다. 임의 port는 이전과 같은 번호가 재사용될 수 있으므로 재생성된 context 식별자로 재시작을 검증한다. 기존 Case·Work Item·계획 해시·승인 해시·Run 참조/상태가 모두 보존되었는지 비교한 뒤 새 MCP/Runner 프로세스로 승인을 재개한다. 완료된 공급망 Run은 한 건 그대로이고 발주도 한 번만 생성된다. 두 context 동안 DB와 MCP 프로세스는 유지하고 전체 시험 종료 시 정리를 수행한다. 이는 정상 application-context 재시작 검증이며 DB 서버/OS 재시작, 강제 프로세스 종료, 실행 중 lease 충돌·장애 복구까지 검증하는 시험은 아니다.

별도의 초기화된 시나리오는 승인 대기 중 시험 fixture가 밀가루 계약 가격을 1,200원에서 1,300원으로 변경한다. 이전 승인 시도는 EXPIRED로 저장되고 발주·후속 업무는 생성되지 않는다. 인간 답변 전에 실제 Runner가 취소된 구매 의존성으로 재개된 부모를 처리하여 ABORTED/BLOCKED 상태로 멈춘다. 방침 Attention은 검증된 같은 Case 안의 미완료 Orchestrator 부모에 연결되어 추가 실행 질문을 중복 생성하지 않는다. 인간은 이 단일 Attention에 기존 계획을 명시한 재계산 지시를 THIS_CASE로 답한다. 답변은 QUEUED를 반환하고 새 프로세스의 부모가 저장된 `epistemic.decisions.decision_id`를 읽어 해당 결정에 한정된 수정 공급망 업무를 배정한다. 이는 새 구매 승인이 아니다. 유효한 부모가 없는 경우 Case 수준 Attention의 NO_WORK_ITEM을 실행 예약으로 취급하지 않는다.

`expire`, `replan`, 최종 `resume`도 별도 프로세스다. 새 공급망 계획 버전은 2이며 새 계획·제안 해시와 17,000원(밀가루 5×1,300 + 설탕 5×1,500 + 포장재 60×50)을 확인한다. 이전 계획 전체와 기존 승인 제안 내용·해시는 불변이며 상태만 EXPIRED다. 새 승인안을 다시 MANAGER가 결정하기 전에는 발주가 없음을 확인한다. 최초 16,500원 승인과 변경 후 17,000원 승인은 독립된 fixture에서 각각 검증한다.
