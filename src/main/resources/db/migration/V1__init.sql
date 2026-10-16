CREATE TABLE tenants (
                         id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                         name       text NOT NULL,
                         slug       text NOT NULL UNIQUE,
                         status     text NOT NULL DEFAULT 'active'
                             CHECK (status IN ('active', 'suspended')),
                         created_at timestamptz NOT NULL DEFAULT now()
);