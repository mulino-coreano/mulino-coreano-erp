━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        MULINO COREANO — Mulino Bianco KR 가상 ERP 추적 흐름도
         (한국 식품위생법 / 이력추적관리법 / 전자세금계산서 기준)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━


┌─────────────────────────────────────────────────────────────────────┐
│  STEP 1. 공급업체 관리                          [suppliers]          │
│                                                                     │
│  공급업체 등록                                                       │
│  ├── 기본 정보 (name, country, contact)                             │
│  └── 📄 인증서 등록 [supplier_certifications]                       │
│        ├── HACCP / ISO22000 / FSSC22000 / GMP                      │
│        ├── ORGANIC / HALAL / KOSHER / 이력추적관리등록              │
│        ├── 발급기관 / 인증번호 / 파일 첨부                           │
│        └── 만료 30일 전 알림 → 만료 시 입고 차단                │
│                                                                     │
│   [Procurement Agent]                                             │
│        └── 인증서 만료 30일 전 자동 감지 → 담당자 알림 발송         │
│            만료 확정 시 → Governance에 입고 차단 요청               │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  STEP 2. 원자재 등록                        [raw_materials]          │
│                                                                     │
│  원자재 기본 정보 등록                                               │
│  ├── name / unit / supplier_id                                      │
│  └──  알레르기 매핑 [raw_material_allergens]                      │
│        ├── 한국 식품위생법 의무 표시 22종 [allergens]               │
│        │     밀 / 갑각류 / 난류 / 어류 / 땅콩 / 대두 / 우유        │
│        │     견과류 / 참깨 / 아황산류 / 조개류 / 복숭아 / 토마토    │
│        │     돼지고기 / 닭고기 / 쇠고기 / 오징어 / 굴 / 전복       │
│        │     홍합 / 잣 / 호두                                       │
│        └── is_trace 여부 표시 (흔적 알레르기도 의무 표시)           │
│                                                                     │
│   [QC Agent]                                                      │
│        └── 신규 원자재 등록 시 알레르겐 미매핑 여부 자동 검증       │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  STEP 3. 구매 발주                          [purchase_orders]        │
│                    ← 신규 추가 (3-Way Match 구현)                   │
│                                                                     │
│  발주 헤더 생성 [purchase_orders]                                    │
│  ├── supplier_id / order_date / expected_delivery_date             │
│  ├── status : DRAFT → ORDERED → PARTIAL → COMPLETED               │
│  ├── created_by → users.user_id                                          │
│  ├──  전자세금계산서 [tax_invoice_number / tax_invoice_date]      │
│  │     └── 한국 B2B 의무 발행 (국세청 전자세금계산서)               │
│  │                                                                  │
│  └── 발주 상세 등록 [purchase_order_items]                          │
│        ├── raw_material_id / quantity / unit_price                  │
│        └── received_quantity (입고 시 누적 업데이트)                │
│                                                                     │
│   [Procurement Agent]                                             │
│        ├── Supply Chain Agent 재발주 요청 수신                      │
│        ├── 발주 Draft 자동 생성                                     │
│        └── → Governance에 승인 요청 (MANAGER 승인 필요)            │
│                                                                     │
│   [Governance Engine]                                             │
│        └── INSERT purchase_orders 가로채기                          │
│            → MANAGER 승인 후 실행 / 미승인 시 차단                  │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  STEP 4. 입고                                   [inbound]           │
│                                                                     │
│  원자재 입고 처리                                                    │
│  ├── purchase_order_item_id → 발주 상세 연결 (3-Way Match)         │
│  ├── raw_material_id / supplier_id / warehouse_id                  │
│  ├── quantity / inbound_date / expiry_date                         │
│  │                                                                  │
│  ├──  운송 온도 기록 [inbound_temperature_logs]                   │
│  │     ├── 상차 시 온도 측정                                         │
│  │     └── 하차 시 온도 측정 → 기준 이탈 시 입고 보류               │
│  │                                                                  │
│  └──  원자재 LOT 생성 [raw_material_lots]                         │
│        ├── lot_number (자체 LOT 번호 부여)                          │
│        ├── supplier_lot_number (공급업체 LOT 번호 연동)             │
│        ├── quantity / remaining_quantity                            │
│        └── production_date / expiry_date                           │
│                                                                     │
│   [QC Agent]                                                      │
│        ├── 입고 온도 기준 이탈 감지 (is_alert = TRUE)               │
│        │     → 해당 입고 건 보류 + QC 검토 요청                     │
│        ├── 알레르겐 미등록 원자재 입고 감지                         │
│        │     → 입고 차단 + 담당자 알림                              │
│        └── 공급업체 인증서 만료 상태 입고 시도 감지                 │
│              → → Governance에 입고 차단 요청                        │
│                                                                     │
│   [Governance Engine]                                             │
│        └── UPDATE inbound status (차단/보류) 가로채기               │
│            → QC 승인 후 실행                                         │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  STEP 5. 창고 보관                              [warehouses]         │
│                                                                     │
│  창고 입고 후 보관                                                   │
│  ├── 원자재 재고 추적 [raw_material_lots.remaining_quantity]        │
│  │                                                                  │
│  └──  창고 온도 상시 기록 [warehouse_temperature_logs]            │
│        ├── 센서 자동 수집 (sensor_id 기록)                          │
│        ├── 상온 창고 / 냉장 0~5°C / 냉동 -18°C 이하               │
│        ├── is_alert = TRUE → 담당자 즉시 알림                       │
│        └── ※ 대량 데이터 → 월별 파티셔닝 권장                       │
│                                                                     │
│   [Supply Chain Agent]                                            │
│        ├── 유통기한 30일 이내 원자재 LOT 자동 감지                  │
│        │     → 우선 사용 권고 알림                                   │
│        └── 재고 소진 예측 (remaining_quantity 추이 분석)            │
│              → N일 내 소진 예상 시 Procurement Agent에 재발주 요청  │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  STEP 6. 생산 / 가공                       [production_records]      │
│                                                                     │
│  생산 공정 기록                                                      │
│  ├── 공정 유형 ENUM (혼합 / 가열 / 냉각 / 포장)                    │
│  ├── operator_id → users.user_id (작업자 추적)                     │
│  ├── warehouse_id / start_time / end_time                          │
│  ├──  가공 온도 기록 (temperature 컬럼)                           │
│  │                                                                  │
│  ├──  투입 원재료 LOT 기록 [production_ingredients]  ◀ 핵심!     │
│  │     ├── 어떤 원재료 LOT이 → 어떤 생산에 투입됐는지              │
│  │     ├── quantity_used (실제 투입량)                             │
│  │     └── remaining_quantity 차감 [raw_material_lots]            │
│  │                                                                  │
│  └──  생산 LOT 생성 [production_lots]                            │
│        ├── lot_number (완제품 LOT 번호)                             │
│        ├── product_id / produced_date / expiry_date                │
│        └── status : ACTIVE / QUARANTINE / RECALLED / CONSUMED      │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  STEP 7. 완제품 재고 등록                         [stock]            │
│                                                                     │
│  ├── product_id / warehouse_id / quantity                           │
│  │     └── 생산 완료 후 애플리케이션 레벨에서 수량 증가 처리        │
│  │                                                                  │
│  └──  창고 온도 계속 모니터링 [warehouse_temperature_logs]        │
│                                                                     │
│   [Supply Chain Agent]                                            │
│        └── 완제품 재고 수준 모니터링                                │
│              → 안전재고 이하 감지 시 생산 계획 알림                  │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  STEP 8. 수주                                     [orders]          │
│                    ← 신규 추가                                      │
│                                                                     │
│  수주 헤더 등록 [orders]                                             │
│  ├── customer_id → customers.id                                    │
│  ├── order_date / expected_delivery_date                           │
│  ├── status : PENDING → CONFIRMED → SHIPPED → CANCELLED           │
│  ├── created_by → users.user_id                                          │
│  └──  전자세금계산서 [tax_invoice_number / tax_invoice_date]      │
│                                                                     │
│  수주 상세 등록 [order_items]                                        │
│  └── product_id / quantity / unit_price                            │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  STEP 9. 출고                                   [outbound]          │
│                                                                     │
│  완제품 출고 처리                                                    │
│  ├── order_id → orders.id (수주 연결)                              │
│  ├── product_id / warehouse_id / quantity / outbound_date          │
│  └──  출고 LOT 연결 [outbound_lots]                              │
│        ├── outbound_id → outbound.id                               │
│        ├── lot_id → production_lots.id (생산 LOT 추적)             │
│        └── lot_quantity (LOT별 부분 출고 수량)                      │
│              ※ SUM(lot_quantity) = outbound.quantity 보장          │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  STEP 10. 리콜 / 당국 대응                        [recalls]          │
│                                                                     │
│  문제 발생 시                                                        │
│  ├── lot_id / raw_lot_id 기반 영향 범위 즉시 파악                   │
│  ├── recall_date / reason / status                                  │
│  │     OPEN → IN_PROGRESS → RESOLVED → CLOSED                      │
│  │                                                                  │
│  ├── 역추적 (Backward Tracing) : 원인 파악                          │
│  │     출고 → outbound_lots → production_lots                       │
│  │          → production_ingredients → raw_material_lots           │
│  │          → inbound → suppliers                                   │
│  │                                                                  │
│  ├── 순추적 (Forward Tracing) : 리콜 범위 파악                      │
│  │     suppliers → inbound → raw_material_lots                     │
│  │          → production_ingredients → production_lots             │
│  │          → outbound_lots → outbound → orders → customers        │
│  │                                                                  │
│  └── 식품의약품안전처 보고 의무                                  │
│        ├── 보고 기한 : 즉시 (사실 인지 후 지체 없이)               │
│        └── 이력 보관 : 2년 (식품이력추적관리법)                     │
│                                                                     │
│   [QC Agent]                                                      │
│        ├── LOT 이상 감지 시 recalls Draft 자동 생성                 │
│        └── → Governance에 승인 요청 (ADMIN 승인 필요)              │
│                                                                     │
│   [Governance Engine]                                             │
│        ├── INSERT recalls 가로채기 → ADMIN 승인 후 확정             │
│        └── UPDATE production_lots.status = 'RECALLED' 가로채기     │
│              → ADMIN 승인 후 실행                                    │
└─────────────────────────────────────────────────────────────────────┘


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                        양방향 추적 요약
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  역추적 (Backward Tracing) : 문제 원인 파악
  ◀────────────────────────────────────────────────────────────────────
  customers ← orders ← outbound ← outbound_lots ← production_lots
  ← production_ingredients ← raw_material_lots ← inbound
  ← purchase_order_items ← purchase_orders ← suppliers

  순추적 (Forward Tracing) : 리콜 범위 파악
  ────────────────────────────────────────────────────────────────────▶
  suppliers → purchase_orders → purchase_order_items → inbound
  → raw_material_lots → production_ingredients → production_lots
  → outbound_lots → outbound → orders → customers


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                    AI 에이전트 개입 시점 요약
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  [Supply Chain Agent]
  ├── STEP 5  유통기한 30일 이내 원자재 LOT 감지 → 우선 사용 권고
  ├── STEP 5  재고 소진 예측 → Procurement Agent에 재발주 요청
  └── STEP 7  완제품 재고 안전재고 이하 감지 → 생산 계획 알림

  [Procurement Agent]
  ├── STEP 1  공급업체 인증서 만료 30일 전 감지 → 담당자 알림
  ├── STEP 3  재발주 요청 수신 → 발주 Draft 생성 → Governance 승인 요청
  └── STEP 4  납품 지연 감지 (expected_delivery_date 초과) → 대체 발주 제안

  [QC Agent]
  ├── STEP 2  신규 원자재 알레르겐 미매핑 감지
  ├── STEP 4  입고 온도 기준 이탈 감지 → 입고 보류
  ├── STEP 4  알레르겐 미등록 원자재 입고 감지 → 입고 차단
  └── STEP 10 LOT 이상 감지 → recalls Draft 생성 → Governance 승인 요청

  [Governance Engine]  ← 모든 액션성 호출 가로채기
  ├── INSERT purchase_orders     → MANAGER 승인 필요
  ├── UPDATE inbound (차단/보류) → QC 승인 필요
  ├── INSERT recalls             → ADMIN 승인 필요
  └── UPDATE production_lots.status = 'RECALLED' → ADMIN 승인 필요
  ※ 조회성 Tool Call은 가로채지 않고 즉시 실행


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                    한국 법규 준수 체크리스트
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  ✅ 알레르기 표시   한국 식품위생법 의무 22종 관리
  ✅ 이력 추적       식품이력추적관리법 — 2년 보관
  ✅ 인증서 관리     HACCP/GMP/이력추적관리등록 만료 관리
  ✅ 온도 기록       입고/창고/가공 전 단계 온도 로그
  ✅ 리콜 대응       식품의약품안전처 즉시 보고 체계
  ✅ 세금계산서      전자세금계산서 승인번호/발행일 관리
  ✅ 원산지 표시     products.country_of_origin 관리
  ✅ 품목 등록       products.registration_number (식약처)
  ✅ 감사 로그       Governance Engine 전체 액션 이력 보관

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                     SAP 모듈 매핑
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  suppliers / supplier_certifications  →  SAP MM  공급업체 마스터
  purchase_orders / purchase_order_items →  SAP MM  구매오더 (ME21N)
  inbound / raw_material_lots          →  SAP MM  입고처리 (MIGO)
  warehouses / stock                   →  SAP EWM 창고관리
  production_records / production_lots →  SAP PP  생산오더
  products                             →  SAP MM  자재 마스터
  orders / order_items / outbound      →  SAP SD  수주오더 (VA01)
  customers                            →  SAP SD  거래처 마스터
  recalls                              →  SAP QM  품질알림
  users                                →  SAP HCM 사용자 관리
  Governance Engine                    →  SAP GRC 거버넌스/컴플라이언스

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
