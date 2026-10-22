package com.pnas.server;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
public abstract class AbstractIntegrationTest {

    /**
     * JVM 级单例 Postgres 容器,由静态初始化器启动一次,供本 JVM 内所有集成测试类共享。
     *
     * <p>不用 {@code @Container}(那会按"测试类"生命周期在 afterAll 停止容器):
     * 同一 JVM 里先后跑多个继承本基类的测试类时,后一个类复用的 Spring 缓存上下文
     * 会指向已被前一个类停掉的端口,报 Connection refused。单例容器在 JVM 退出前
     * 一直存活(由 Testcontainers Ryuk 回收),无论单类还是整包运行都稳定。</p>
     */
    static final PostgreSQLContainer<?> POSTGRES = startPostgres();

    private static PostgreSQLContainer<?> startPostgres() {
        var postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        return postgres;
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("pnas.data-dir", () -> "build/test-data/" + System.nanoTime());
        r.add("pnas.security.require-tls", () -> "false");
        r.add("pnas.bootstrap.admin-username", () -> "admin");
        r.add("pnas.bootstrap.admin-password", () -> "admin-secret");
        // 测试中关闭周期调度,任务由测试显式同步执行,避免并发干扰断言
        r.add("pnas.jobs.scheduler-enabled", () -> "false");
        // 月份桶 SQL 用 PostgreSQL 会话 TimeZone(to_char/date_part),而 PgJDBC 只在建连时
        // 读取 JVM 默认时区;连接池里的旧连接不会跟随测试 @BeforeEach 的时区钉子。
        // 固定测试基准时区,保证"EXIF 本地墙钟 → 月桶"两端使用同一时区语义。
        r.add("spring.datasource.hikari.connection-init-sql", () -> "SET TIME ZONE 'Asia/Shanghai'");
    }
}
