ALTER TABLE email_messages ADD CONSTRAINT email_messages_tenant_id_id_key UNIQUE (tenant_id, id);

CREATE TABLE delivery_attempts (
                                   id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                   tenant_id           uuid NOT NULL,
                                   message_id          uuid NOT NULL,
                                   attempt_number      int NOT NULL,
                                   outcome             text NOT NULL
                                       CHECK (outcome IN ('sent', 'transient_failure', 'permanent_failure', 'uncertain', 'dropped')),
                                   error               text,
                                   provider_message_id text,
                                   created_at          timestamptz NOT NULL DEFAULT now(),
                                   UNIQUE (message_id, attempt_number),
                                   FOREIGN KEY (tenant_id, message_id) REFERENCES email_messages (tenant_id, id)
);
CREATE INDEX delivery_attempts_message_idx ON delivery_attempts (tenant_id, message_id);

ALTER TABLE delivery_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE delivery_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON delivery_attempts
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- Worker role: no superuser, no BYPASSRLS. Dev-only password; production creates it outside migrations.
DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'flowmail_worker') THEN
            CREATE ROLE flowmail_worker LOGIN PASSWORD 'flowmail_worker' NOSUPERUSER NOBYPASSRLS;
        END IF;
    END
$$;

GRANT USAGE ON SCHEMA public TO flowmail_worker;
GRANT SELECT, UPDATE ON email_messages, campaigns TO flowmail_worker;
GRANT SELECT ON contacts, suppression_entries TO flowmail_worker;
GRANT INSERT ON delivery_attempts TO flowmail_worker;

-- These policies are ORed with the tenant_isolation policies, and apply to the worker role only.
CREATE POLICY worker_access ON email_messages FOR ALL TO flowmail_worker USING (true) WITH CHECK (true);
CREATE POLICY worker_access ON campaigns FOR ALL TO flowmail_worker USING (true) WITH CHECK (true);
CREATE POLICY worker_read ON contacts FOR SELECT TO flowmail_worker USING (true);
CREATE POLICY worker_read ON suppression_entries FOR SELECT TO flowmail_worker USING (true);
CREATE POLICY worker_insert ON delivery_attempts FOR INSERT TO flowmail_worker WITH CHECK (true);