-- Required logical owner for ACT intake. Existing inactive agents remain inactive.
INSERT INTO agents (agent_key, display_name, role_scope, is_active)
VALUES ('ORCHESTRATOR', '오케스트레이터', 'Case intake and specialist routing', true)
ON CONFLICT (agent_key) DO NOTHING;

-- Stable generic origins prevent intake from attaching a Case to an arbitrary
-- Slack or email thread that happens to share the same channel type.
INSERT INTO channels (channel_type, external_ref, display_name)
VALUES
    ('CHAT',      'SYSTEM_DEFAULT', '기본 ChatGPT/Claude 채널'),
    ('SLACK',     'SYSTEM_DEFAULT', '기본 Slack 채널'),
    ('EMAIL',     'SYSTEM_DEFAULT', '기본 Email 채널'),
    ('DASHBOARD', 'SYSTEM_DEFAULT', '기본 Dashboard 채널'),
    ('API',       'SYSTEM_DEFAULT', '기본 API 채널')
ON CONFLICT (channel_type, external_ref) DO NOTHING;
