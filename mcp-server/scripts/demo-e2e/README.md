# 로컬 데모 E2E 수용 시험

`demoE2eTest`는 PostgreSQL 18, 실제 Spring Boot HTTP 서버, Streamable HTTP MCP 서버와 SDK 클라이언트, 기존 `Runner`·`WorkerApi`·`ProcessExecutor`, 실제 호스트 Zig CLI를 연결한다. 일반 `test`에서는 `demo-e2e` 태그를 제외하며 이 시험의 인증 서버나 Node 프로세스를 시작하지 않는다.

시뮬레이션 경계는 두 가지다. Auth0 대신 시험에서 생성한 RS256 키로 인간·OBO·M2M 토큰을 발급한다. 외부 신원 제공자 요청만 주입한 fetch가 처리하고 ERP 요청은 실제 HTTP로 전송한다. 백엔드는 실제 `ErpJwtDecoder`, HTTPS JWKS, `ActorJwtConverter`, DB의 `external_identities`와 역할을 사용한다. 임시 자체 서명 인증서는 시험 JVM에서만 신뢰하며 종료 시 이전 TLS 설정을 복원하고 파일을 제거한다. TLS 검증을 끄지 않는다.

사업 모델 대신 `model.mjs` 자식 프로세스가 고정된 역할 판단을 수행한다. 모든 사업 조회·변경은 컴파일된 `mulino`를 호출하며 최종 응답은 실제 `item.completed`/`agent_message` 이벤트로 반환한다. 자식에게는 Run capability만 전달하며 인간 토큰, OBO/M2M secret, DB 자격 증명은 전달하지 않는다. DB 직접 접근은 Java 시험의 초기 fixture 및 사후 검증만 담당한다. 이 결과는 실제 Auth0 tenant 설정, 외부 채팅 클라이언트 로그인, Codex 모델 실행, DockerExecutor의 격리 수용 시험을 대신하지 않는다.

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
