-- =============================================================================
-- 06_audit_immutability.sql: governance_audit_logs 불변성 강제
-- =============================================================================
-- 규제 증빙(리콜/원인분석)을 위해 감사 로그는 수정·삭제가 불가해야 한다.
-- 05_foreign_keys.sql까지 실행한 뒤 적용한다.

CREATE OR REPLACE FUNCTION prevent_audit_log_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'governance_audit_logs is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_governance_audit_logs_no_update
    BEFORE UPDATE ON governance_audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_modification();

CREATE TRIGGER trg_governance_audit_logs_no_delete
    BEFORE DELETE ON governance_audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_modification();
