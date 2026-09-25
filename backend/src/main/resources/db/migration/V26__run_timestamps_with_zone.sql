-- runs.claimed_at·lease_expires_at은 timestamptz인데 started_at·finished_at만 timestamp였다.
-- 앱은 JVM 시간대(Asia/Seoul)의 벽시계 시각을 썼으므로 그 기준으로 시점을 복원한다.
-- UTC 세션에서 읽으면 같은 Run의 시각이 9시간 어긋났다(#54).
ALTER TABLE runs
    ALTER COLUMN started_at TYPE TIMESTAMPTZ USING started_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN finished_at TYPE TIMESTAMPTZ USING finished_at AT TIME ZONE 'Asia/Seoul';
