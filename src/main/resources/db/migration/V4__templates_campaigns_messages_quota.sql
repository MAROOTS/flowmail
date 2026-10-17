ALTER TABLE tenants
    ADD COLUMN monthly_email_limit int NOT NULL DEFAULT 1000 CHECK (monthly_email_limit >= 0);

CREATE TABLE templates (
                           id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                           tenant_id  uuid NOT NULL REFERENCES tenants(id),
                           name       text NOT NULL,
                           subject    text NOT NULL,
                           html_body  text NOT NULL,
                           text_body  text,
                           created_by uuid NOT NULL REFERENCES users(id),
                           created_at timestamptz NOT NULL DEFAULT now(),
                           updated_at timestamptz NOT NULL DEFAULT now(),
                           UNIQUE (tenant_id, id),
                           UNIQUE (tenant_id, name)
);

CREATE TABLE campaigns (
                           id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                           tenant_id        uuid NOT NULL REFERENCES tenants(id),
                           name             text NOT NULL,
                           template_id      uuid NOT NULL,
                           from_name        text NOT NULL,
                           from_email       text NOT NULL,
                           status           text NOT NULL DEFAULT 'draft'
                               CHECK (status IN ('draft', 'queued', 'sending', 'paused', 'completed', 'canceled')),
                           recipient_count  int NOT NULL DEFAULT 0,
                           snapshot_subject text,
                           snapshot_html    text,
                           snapshot_text    text,
                           launched_at      timestamptz,
                           created_by       uuid NOT NULL REFERENCES users(id),
                           created_at       timestamptz NOT NULL DEFAULT now(),
                           updated_at       timestamptz NOT NULL DEFAULT now(),
                           UNIQUE (tenant_id, id),
                           FOREIGN KEY (tenant_id, template_id) REFERENCES templates (tenant_id, id)
);
CREATE INDEX campaigns_tenant_created_idx ON campaigns (tenant_id, created_at DESC);

CREATE TABLE email_messages (
                                id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                tenant_id           uuid NOT NULL,
                                campaign_id         uuid NOT NULL,
                                contact_id          uuid NOT NULL,
                                email               text NOT NULL,
                                status              text NOT NULL DEFAULT 'queued'
                                    CHECK (status IN ('queued', 'sending', 'sent', 'delivered', 'bounced',
                                                      'complained', 'failed', 'unknown', 'canceled')),
                                attempt_count       int NOT NULL DEFAULT 0,
                                next_attempt_at     timestamptz NOT NULL DEFAULT now(),
                                locked_until        timestamptz,
                                locked_by           text,
                                provider_message_id text,
                                last_error          text,
                                sent_at             timestamptz,
                                created_at          timestamptz NOT NULL DEFAULT now(),
                                updated_at          timestamptz NOT NULL DEFAULT now(),
                                UNIQUE (campaign_id, contact_id),
                                FOREIGN KEY (tenant_id, campaign_id) REFERENCES campaigns (tenant_id, id),
                                FOREIGN KEY (tenant_id, contact_id) REFERENCES contacts (tenant_id, id)
);
CREATE INDEX email_messages_campaign_idx ON email_messages (tenant_id, campaign_id, status);
CREATE INDEX email_messages_due_idx ON email_messages (next_attempt_at)
    WHERE status IN ('queued', 'sending');

CREATE TABLE usage_counters (
                                tenant_id uuid NOT NULL REFERENCES tenants(id),
                                period    date NOT NULL,
                                used      int NOT NULL DEFAULT 0 CHECK (used >= 0),
                                PRIMARY KEY (tenant_id, period)
);

ALTER TABLE templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE templates FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON templates
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE campaigns ENABLE ROW LEVEL SECURITY;
ALTER TABLE campaigns FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON campaigns
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE email_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_messages FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_messages
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE usage_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_counters FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON usage_counters
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);