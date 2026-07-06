CREATE TYPE user_role AS ENUM(
    'ADMIN',
    'MANAGER',
    'OPERATOR',
    'QC',
    'VIEWER'
);

CREATE TYPE supplier_certification_cert_type AS ENUM(
    'HACCP',
    'ISO22000',
    'FSSC22000',
    'GMP',
    'ORGANIC',
    'HALAL',
    'KOSHER',
    'TRACEABILITY'
);

CREATE TYPE purchase_order_status AS ENUM(
    'DRAFT',
    'ORDERED',
    'PARTIAL',
    'COMPLETED'
);

CREATE TYPE production_record_process_type AS ENUM(
    'MIX',
    'HEAT',
    'COOL',
    'PACK'
);

CREATE TYPE production_lot_status AS ENUM(
    'ACTIVE',
    'QUARANTINE',
    'RECALLED',
    'CONSUMED'
);

CREATE TYPE order_status AS ENUM(
    'PENDING',
    'CONFIRMED',
    'SHIPPED',
    'CANCELLED'
);

CREATE TYPE recall_status AS ENUM(
    'OPEN',
    'IN_PROGRESS',
    'RESOLVED',
    'CLOSED'
);

CREATE TYPE warehouse_type AS ENUM(
    'AMBIENT',
    'CHILLED',
    'FROZEN'
);