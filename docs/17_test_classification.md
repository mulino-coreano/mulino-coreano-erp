# #57 테스트 분류와 삭제 범위

문구와 빈 context 검증을 줄이되 승인·업무·보안의 기존 증거를
임의로 없애지 않는다. 2026-10-03 소유자가 승인한 삭제는 아래 3건이다.
그 밖의 기존 테스트 삭제는 이번 작업에서 승인되지 않았다.

| 기존 테스트 | 분류 | 적용 이유와 범위 |
|---|---|---|
| `BackendApplicationTests.contextLoads` | 삭제 | 빈 context 검증. 실제 업무 SIT가 Spring 구동과 업무 상태를 검증한다. |
| `GlobalExceptionHandlerTest.responseStatusExceptionKeepsItsHttpStatus` | 삭제 | 응답 문구·코드 중심 probe. 기존 실제 HTTP/API 통합 검증을 유지한다. |
| `GlobalExceptionHandlerTest.annotatedConflictExceptionsStayConflicts` | 삭제 | 합성 conflict probe. 실제 업무 충돌·idempotency 검증을 유지한다. |
| `GlobalExceptionHandlerTest.undeclaredFailuresStayInternal` | 유지 | 예상하지 않은 실패를 성공이나 다른 업무 오류로 바꾸지 않는 HTTP 경계를 검증한다. 소유자가 명시적으로 유지했다. |
| 나머지 기존 469건 | 유지 | 이번 삭제 승인 범위 밖이다. 기존 업무·권한·가격 정밀도·lease 검증을 그대로 유지했다. 전수 품질 재분류를 수행했다는 뜻은 아니다. |

현재 부모 `c5f1958`에서 473건이 통과했고 실행 시간은 82초였다.
이번 삭제만 적용하면 기존 테스트는 470건이다. 새 scenario helper
12건을 더한 Unit 실행은 482건이다. Cucumber가 같은 classpath에서
발견한 업무 시나리오 13건은 Unit task에서 제외하여 skipped로
보고하므로 JUnit XML 발견 합계는 495건이다.
최종 `test bootJar` 실행 시간은 92초였다. 원본 stack의 오래된 546건이나
402건을 현재 branch의 전후 수치로 쓰지 않는다.

MRP→P2P 전체 흐름은 새 SIT로 검증한다. 부모에는 `DemoE2eTest`가
없으므로 이 파일을 이식하거나 삭제한 것으로 보고하지 않는다.
새 helper는 timeout·프로세스 종료·폐기용 DB guard·Run 사용량 합산·
증거 파일의 업무 결과 보존을 검증한다. source helper의 오류 문구
단언은 실패·시간 제한·실제 프로세스 종료 검증으로 고쳐 썼다.
