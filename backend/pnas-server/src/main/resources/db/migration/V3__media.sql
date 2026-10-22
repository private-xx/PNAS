-- M3 媒体时间线索引(spec §4)
create table media_assets (
    id                uuid primary key,
    file_version_id   uuid not null unique references file_versions(id) on delete cascade,
    node_id           uuid not null references nodes(id) on delete cascade,
    kind              text not null,
    taken_at          timestamptz not null,
    taken_at_source   text not null,
    width             int,
    height            int,
    gps_lat           double precision,
    gps_lon           double precision,
    duration_ms       bigint,
    thumb_status      text not null,
    indexed_at        timestamptz not null
);

create index ix_media_timeline on media_assets (taken_at desc, id desc);
create index ix_media_node     on media_assets (node_id);
create index ix_media_thumb    on media_assets (thumb_status);
