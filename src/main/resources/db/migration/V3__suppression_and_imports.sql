ALTER TABLE contacts ADD COLUMN unsubscribed_at timestamptz;

CREATE TABLE suppression_entries (
                                     id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                     tenant_id  uuid NOT NULL REFERENCES tenants(id),
                                     email      text NOT NULL,
                                     reason     text NOT NULL CHECK (reason IN ('unsubscribe', 'bounce', 'complaint', 'manual')),
                                     source     text,
                                     created_at timestamptz NOT NULL DEFAULT now(),
                                     UNIQUE (tenant_id, email)
);

CREATE TABLE import_jobs (
                             id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                             tenant_id        uuid NOT NULL REFERENCES tenants(id),
                             created_by       uuid NOT NULL REFERENCES users(id),
                             filename         text NOT NULL,
                             status           text NOT NULL CHECK (status IN ('completed', 'failed')),
                             total_rows       int NOT NULL,
                             created_count    int NOT NULL,
                             updated_count    int NOT NULL,
                             suppressed_count int NOT NULL,
                             failed_count     int NOT NULL,
                             errors_truncated boolean NOT NULL DEFAULT false,
                             created_at       timestamptz NOT NULL DEFAULT now(),
                             UNIQUE (tenant_id, id)
);

CREATE TABLE import_row_errors (
                                   id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                   tenant_id uuid NOT NULL,
                                   import_id uuid NOT NULL,
                                   row_num   int NOT NULL,
                                   email     text,
                                   message   text NOT NULL,
                                   FOREIGN KEY (tenant_id, import_id) REFERENCES import_jobs (tenant_id, id)
);
CREATE INDEX import_row_errors_import_idx ON import_row_errors (tenant_id, import_id, row_num);

ALTER TABLE suppression_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE suppression_entries FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON suppression_entries
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE import_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON import_jobs
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE import_row_errors ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_row_errors FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON import_row_errors
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);