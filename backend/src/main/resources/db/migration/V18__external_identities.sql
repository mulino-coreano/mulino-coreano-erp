-- Auth0 신원은 이메일 자동 매칭 없이 관리자가 ERP 사용자에 사전 연결한다.
-- 로컬 비밀번호 로그인은 이 migration으로 추가하지 않는다.
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

CREATE TABLE external_identities (
    external_identity_id BIGSERIAL PRIMARY KEY,
    issuer TEXT NOT NULL CHECK (length(btrim(issuer)) > 0),
    subject TEXT NOT NULL CHECK (length(btrim(subject)) > 0),
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_external_identity_issuer_subject UNIQUE (issuer, subject)
);

CREATE INDEX idx_external_identities_user ON external_identities(user_id);
COMMENT ON TABLE external_identities IS '사전 등록한 외부 issuer/subject와 ERP 인간 사용자의 연결';
