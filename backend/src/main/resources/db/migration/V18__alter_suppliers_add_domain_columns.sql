-- V18__alter_suppliers_add_domain_columns.sql
-- Supplier 도메인 A단계: 결제조건/통화/주소 세분화/updated_at 추가
-- 결정: C(payment_terms ENUM), D(currency), E(주소 세분화), F(updated_at), L①(JPA Auditing)

-- [C] 결제조건 ENUM 타입 신설
CREATE TYPE payment_terms AS ENUM (
    'NET_30',
    'NET_60',
    'COD',
    'PREPAID'
);

-- suppliers 테이블 컬럼 추가
ALTER TABLE suppliers
    ADD COLUMN address_line   VARCHAR(255)  NULL,                        -- [E] 주소 세분화
    ADD COLUMN city           VARCHAR(100)  NULL,
    ADD COLUMN postal_code    VARCHAR(20)   NULL,
    ADD COLUMN payment_terms  payment_terms NOT NULL DEFAULT 'NET_30',   -- [C] 결제조건
    ADD COLUMN currency       CHAR(3)       NOT NULL DEFAULT 'KRW',      -- [D] 통화(ISO 4217)
    ADD COLUMN updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP; -- [F] audit (갱신은 JPA Auditing이 담당)