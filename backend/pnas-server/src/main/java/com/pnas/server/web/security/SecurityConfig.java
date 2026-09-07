package com.pnas.server.web.security;

import com.pnas.server.auth.ServerSessionRepository;
import com.pnas.server.iam.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    public static final String COOKIE = "PNAS_SESSION";
    public static final String CSRF_HEADER = "X-CSRF";

    @Bean
    PasswordEncoder passwordEncoder() { return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, ServerSessionRepository sessions,
                              UserRepository users) throws Exception {
        // 过滤器在此处内联实例化(而非注册为 @Bean),避免 Spring Boot 将其当作
        // 通用 servlet Filter 二次自动注册,导致同一请求内重复执行两遍。
        SessionAuthFilter sessionFilter = new SessionAuthFilter(sessions, users);
        CsrfGuardFilter csrf = new CsrfGuardFilter(sessions);
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/session").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(csrf, SessionAuthFilter.class);
        return http.build();
    }
}
