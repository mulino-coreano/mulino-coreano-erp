-- #54: started_at/finished_at were local wall-clock values in the app's Asia/Seoul
-- JDBC session, unlike claimed_at/lease_expires_at. Recover those known legacy instants
-- explicitly; the migration session timezone must not change their meaning.
ALTER TABLE runs
    ALTER COLUMN started_at TYPE TIMESTAMPTZ USING started_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN finished_at TYPE TIMESTAMPTZ USING finished_at AT TIME ZONE 'Asia/Seoul';
