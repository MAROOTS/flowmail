package com.flowmail.common;

import com.flowmail.tenancy.TenantJwtAuthenticationConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    TenantJwtAuthenticationConverter jwtConverter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/unsubscribe/**").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/contacts/**", "/api/suppressions/**",
                                "/api/templates/**", "/api/campaigns/**")
                        .hasAnyRole("OWNER", "MANAGER", "VIEWER")
                        .requestMatchers(HttpMethod.DELETE, "/api/suppressions/**").hasRole("OWNER")
                        .requestMatchers("/api/contacts/**", "/api/suppressions/**",
                                "/api/templates/**", "/api/campaigns/**")
                        .hasAnyRole("OWNER", "MANAGER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
                .build();
    }
}