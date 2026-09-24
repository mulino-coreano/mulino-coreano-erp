-- ERP에는 비밀번호 로그인이 없다. 사람 신원은 인증 계층이 정한다(현재 PoC 로컬 신원,
-- Auth0/OAuth는 #21·#22에서 보류). 사용자 행은 비밀번호 없이 만들 수 있어야 한다.
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;
