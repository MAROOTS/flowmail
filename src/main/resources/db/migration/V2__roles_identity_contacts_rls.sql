-- Runtime role: no superuser, no BYPASSRLS, owns nothing.
-- Dev-only password. In production, create this role outside migrations with a secret.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'flowmail_app') THEN
    CREATE ROLE flowmail_app LOGIN PASSWORD 'flowmail_app' NOSUPERUSER NOBYPASSRLS;
  END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO flowmail_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenants TO flowmail_app;

-- Tables created by later migrations are granted automatically.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO flowmail_app;

-- Identity tables: global, accessed only by the auth module with explicit predicates.
CREATE TABLE users (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email         text NOT NULL,
  password_hash text NOT NULL,
  display_name  text NOT NULL,
  status        text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_lower_uq ON users (lower(email));

CREATE TABLE memberships (
  tenant_id  uuid NOT NULL REFERENCES tenants(id),
  user_id    uuid NOT NULL REFERENCES users(id),
  role       text NOT NULL CHECK (role IN ('owner', 'manager', 'viewer')),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, user_id)
);
CREATE INDEX memberships_user_idx ON memberships (user_id);

-- First tenant-owned table. Every tenant-owned table will follow this pattern.
CREATE TABLE contacts (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  uuid NOT NULL REFERENCES tenants(id),
  email      text NOT NULL,
  first_name text,
  last_name  text,
  status     text NOT NULL DEFAULT 'subscribed'
             CHECK (status IN ('subscribed', 'unsubscribed', 'bounced', 'complained')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, email)
);

ALTER TABLE contacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE contacts FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON contacts
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
