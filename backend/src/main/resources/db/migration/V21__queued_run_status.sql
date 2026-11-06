-- PostgreSQL enum additions must commit before a later migration uses the value.
ALTER TYPE run_status ADD VALUE 'QUEUED';
