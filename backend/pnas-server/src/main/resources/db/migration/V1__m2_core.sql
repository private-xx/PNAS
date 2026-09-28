-- PNAS M2 核心表(2026-09-07)。命名 snake_case,与 Hibernate 默认物理命名一致。
create table users (
    id uuid primary key default gen_random_uuid(),
    username text not null unique,
    display_name text not null default '',
    password_hash text not null,
    role text not null check (role in ('ADMIN','MEMBER')),
    status text not null default 'ACTIVE' check (status in ('ACTIVE','DISABLED')),
    created_at timestamptz not null default now()
);

create table groups (
    id uuid primary key default gen_random_uuid(),
    name text not null unique,
    created_at timestamptz not null default now()
);

create table group_members (
    user_id uuid not null references users(id) on delete cascade,
    group_id uuid not null references groups(id) on delete cascade,
    primary key (user_id, group_id)
);

create table http_sessions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users(id) on delete cascade,
    token_hash text not null unique,
    csrf_hash text not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    revoked_at timestamptz
);

create table nodes (
    id uuid primary key default gen_random_uuid(),
    parent_id uuid references nodes(id) on delete set null,
    owner_id uuid not null references users(id),
    kind text not null check (kind in ('DIR','FILE')),
    name text not null,
    size_bytes bigint not null default 0,
    trashed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create unique index ux_nodes_parent_name on nodes(parent_id, name)
    where trashed_at is null;

create table acl_entries (
    id uuid primary key default gen_random_uuid(),
    node_id uuid not null references nodes(id) on delete cascade,
    principal_type text not null check (principal_type in ('USER','GROUP')),
    principal_id uuid not null,
    perms text not null,
    inherited boolean not null default true
);
create unique index ux_acl on acl_entries(node_id, principal_type, principal_id);

create table file_versions (
    id uuid primary key default gen_random_uuid(),
    node_id uuid not null references nodes(id) on delete cascade,
    version_no int not null,
    size_bytes bigint not null,
    mime_type text not null default 'application/octet-stream',
    manifest_sha256 text not null,
    created_by uuid references users(id),
    created_at timestamptz not null default now(),
    unique (node_id, version_no)
);

create table version_chunks (
    id uuid primary key default gen_random_uuid(),
    version_id uuid not null references file_versions(id) on delete cascade,
    seq int not null,
    blob_hash text not null,
    size_bytes bigint not null,
    unique (version_id, seq)
);

create table upload_sessions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users(id),
    dest_parent_id uuid not null references nodes(id),
    filename text not null,
    total_size bigint not null,
    chunk_size int not null,
    state text not null default 'OPEN' check (state in ('OPEN','COMPLETE','CANCELLED')),
    received_chunks jsonb not null default '[]',
    chunk_hashes jsonb not null default '{}',
    chunk_sizes jsonb not null default '{}',
    created_at timestamptz not null default now()
);

create table jobs (
    id uuid primary key default gen_random_uuid(),
    type text not null,
    payload jsonb not null default '{}',
    state text not null default 'QUEUED'
        check (state in ('QUEUED','RUNNING','SUCCESS','FAILED','DEAD')),
    attempt int not null default 0,
    max_attempts int not null default 3,
    priority int not null default 0,
    next_run_at timestamptz not null default now(),
    started_at timestamptz,
    finished_at timestamptz,
    error text,
    result jsonb
);
create index ix_jobs_claim on jobs(state, next_run_at);
