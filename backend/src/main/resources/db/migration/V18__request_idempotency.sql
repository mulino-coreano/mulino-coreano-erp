CREATE TABLE request_idempotency (
    scope VARCHAR(200) NOT NULL,
    request_key VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scope,request_key),
    CONSTRAINT ck_request_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_request_idempotency_response CHECK (jsonb_typeof(response)='object')
);
