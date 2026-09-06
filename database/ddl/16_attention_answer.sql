-- Attention versions invalidate stale human answers on every update.
UPDATE attention_requests SET version=1 WHERE version IS NULL;
ALTER TABLE attention_requests ALTER COLUMN version SET NOT NULL;
CREATE FUNCTION increment_attention_version() RETURNS TRIGGER AS $$
BEGIN
    NEW.version := OLD.version + 1;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_attention_version BEFORE UPDATE ON attention_requests
    FOR EACH ROW EXECUTE FUNCTION increment_attention_version();
