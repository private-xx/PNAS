-- V4: M4a 设备与备份运行
create table devices (
    id           uuid primary key,
    user_id      uuid not null references users(id) on delete cascade,
    name         text not null,
    platform     text not null,
    pat_hash     text not null unique,
    created_at   timestamptz not null default now(),
    last_seen_at timestamptz,
    revoked_at   timestamptz
);
create index ix_devices_user on devices (user_id);

create table backup_runs (
    id              uuid primary key,
    device_id       uuid not null references devices(id) on delete cascade,
    user_id         uuid not null references users(id) on delete cascade,
    mode            text not null,
    status          text not null,
    started_at      timestamptz not null default now(),
    finished_at     timestamptz,
    files_seen      int not null default 0,
    files_uploaded  int not null default 0,
    files_skipped   int not null default 0,
    files_failed    int not null default 0,
    bytes_uploaded  bigint not null default 0,
    error           text
);
create index ix_backup_runs_device on backup_runs (device_id, started_at desc);

alter table file_versions add column source_mtime timestamptz;
alter table upload_sessions add column source_mtime timestamptz;
