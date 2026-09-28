package com.pnas.server;

import com.pnas.server.iam.UserService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * PNAS Server 入口。
 *
 * <p>模块化单体:单进程同时承载 Web API 与后台 worker;
 * 通过 profile/启动参数可拆出独立 worker 进程(见架构设计 ADR-01)。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PnasServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PnasServerApplication.class, args);
    }

    @Bean
    CommandLineRunner bootstrapAdmin(UserService userService) {
        return args -> userService.bootstrapAdmin();
    }
}
