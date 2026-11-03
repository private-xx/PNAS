package com.pnas.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayMigratesAllCoreTables() {
        Integer n = jdbc.queryForObject("""
            select count(*) from information_schema.tables
            where table_schema='public' and table_name in
            ('users','groups','group_members','nodes','acl_entries',
             'file_versions','version_chunks','upload_sessions','http_sessions','jobs')
            """, Integer.class);
        assertThat(n).isEqualTo(10);
    }

    @Test
    void mediaAssetsTableHasExpectedColumns() {
        var columns = jdbc.queryForList(
            "select column_name from information_schema.columns where table_name = 'media_assets'",
            String.class);
        assertThat(columns).contains("id", "file_version_id", "node_id", "kind", "taken_at",
            "taken_at_source", "width", "height", "gps_lat", "gps_lon", "duration_ms",
            "thumb_status", "indexed_at");
    }

    @Test
    void backupTablesExistWithExpectedColumns() {
        var devices = jdbc.queryForList(
            "select column_name from information_schema.columns where table_name = 'devices'", String.class);
        assertThat(devices).contains("id", "user_id", "name", "platform", "pat_hash",
            "created_at", "last_seen_at", "revoked_at");
        var runs = jdbc.queryForList(
            "select column_name from information_schema.columns where table_name = 'backup_runs'", String.class);
        assertThat(runs).contains("id", "device_id", "user_id", "mode", "status", "started_at",
            "finished_at", "files_seen", "files_uploaded", "files_skipped", "files_failed",
            "bytes_uploaded", "error");
        var fv = jdbc.queryForList(
            "select column_name from information_schema.columns where table_name = 'file_versions'", String.class);
        assertThat(fv).contains("source_mtime");
    }
}
