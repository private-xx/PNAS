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
    }
}
