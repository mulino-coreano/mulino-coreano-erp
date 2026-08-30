-- =============================================================================
-- 01_master_tables.sql: 기본 마스터 테이블 정의
-- =============================================================================

CREATE TABLE users (
    user_id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role user_role NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE suppliers (
    supplier_id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    country VARCHAR(50) NOT NULL,
    contact_name VARCHAR(50),
    contact_email VARCHAR(100),
    contact_phone VARCHAR(20),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE products (
    product_id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    sku VARCHAR(50) NOT NULL UNIQUE,
    unit VARCHAR(20) NOT NULL,
    expiry_days INT NOT NULL,
    product_type product_type NOT NULL DEFAULT 'FINISHED_GOODS',
    registration_number VARCHAR(50), -- 식약처 품목제조보고번호
    trace_code VARCHAR(50),          -- 식약처 식품이력추적관리 등록번호
    country_of_origin VARCHAR(50),
    attributes JSONB NULL,           -- 비건, Non-GMO, 영양성분 등 확장 메타데이터
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE raw_materials (
    raw_material_id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    material_type material_type NOT NULL DEFAULT 'INGREDIENT',
    supplier_id BIGINT NOT NULL,
    attributes JSONB NULL,           -- 규격, 원산지, 보관조건 등 확장 메타데이터
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE customers (
    customer_id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    contact_name VARCHAR(50),
    contact_email VARCHAR(100),
    contact_phone VARCHAR(20),
    address VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE allergens (
    allergen_id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    code VARCHAR(20) NULL,                                  -- 식약처/표준 알레르겐 코드
    legal_category VARCHAR(50) NOT NULL DEFAULT '기타',      -- 19개 법정 표시의무 군 분류
    standard VARCHAR(20) NOT NULL DEFAULT 'KR_MFDS'         -- 표준 (KR_MFDS, EU_EFSA 등)
);

CREATE TABLE warehouses (
    warehouse_id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    location VARCHAR(100),
    type warehouse_type NOT NULL,
    plant_id VARCHAR(50) NULL,                              -- 공장/사업장 식별자 (확장성)
    parent_warehouse_id BIGINT NULL,                           -- 구역/Zone 계층화 (확장성)
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
