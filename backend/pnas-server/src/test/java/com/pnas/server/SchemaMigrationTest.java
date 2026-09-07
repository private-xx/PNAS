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
}
