# GitHub 라벨 정의

이슈 및 PR 분류를 위한 라벨 체계입니다. GitHub 레포 → Issues → Labels 에서 생성합니다.

## 카테고리 라벨

| 라벨 | 색상(권장) | 설명 |
|---|---|---|
| `feature` | 초록 (#0e8a16) | 새 기능 구현 |
| `bug` | 빨강 (#d73a4a) | 버그 및 오류 |
| `research` | 보라 (#8250df) | 사전조사, 법규 분석 |
| `qc` | 노랑 (#fbca04) | 검증, 테스트, 리뷰 |
| `docs` | 파랑 (#0075ca) | 문서 작업 |
| `chore` | 회색 (#cfd3d7) | 환경설정, 빌드 |

## 레이어 라벨

| 라벨 | 색상(권장) | 설명 |
|---|---|---|
| `L0-db` | 하늘 (#c5def5) | PostgreSQL, 스키마 |
| `L0-backend` | 하늘 (#c5def5) | Spring Boot, MCP |
| `L1-governance` | 하늘 (#c5def5) | 거버넌스 엔진 |
| `L2-agent` | 하늘 (#c5def5) | 멀티 에이전트 |
| `L3-dashboard` | 하늘 (#c5def5) | 대시보드 |

## 우선순위 라벨

| 라벨 | 색상(권장) | 설명 |
|---|---|---|
| `priority: high` | 진빨강 (#b60205) | 지금 안 하면 다음 단계 막힘 |
| `priority: medium` | 주황 (#d93f0b) | 중요하지만 병렬 가능 |
| `priority: low` | 연회색 (#bfdadc) | 여유 있을 때 |

## 상태 라벨 (선택)

| 라벨 | 색상(권장) | 설명 |
|---|---|---|
| `blocked` | 검정 (#000000) | 의존성/이슈로 막힘 |
| `in progress` | 노랑 (#fbca04) | 진행 중 |
