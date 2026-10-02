-- Attention versions invalidate stale human answers on every update.
UPDATE attention_requests SET version=1 WHERE version IS NULL;
ALTER TABLE attention_requests ALTER COLUMN version SET NOT NULL;
CREATE FUNCTION increment_attention_version() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.version IS NULL OR NEW.version <= 0 THEN
        RAISE EXCEPTION 'Attention version must be a positive integer' USING ERRCODE='23514';
    END IF;
    NEW.version := OLD.version + 1;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_attention_version BEFORE UPDATE ON attention_requests
    FOR EACH ROW EXECUTE FUNCTION increment_attention_version();
