package co.kr.satu.imagegate.security.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, co.kr.satu.imagegate.audit.config.AuditRequestFilter audit, co.kr.satu.imagegate.media.config.MultipartIngressFilter ingress) throws Exception {
        // Keep CSRF protection enabled. Authentication and per-media authorization
        // must be integrated before any media endpoint is opened.
        return http
                .addFilterAfter(audit,org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .addFilterBefore(ingress,org.springframework.security.web.csrf.CsrfFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/viewer","/viewer/",
                                "/viewer/viewer.css","/viewer/viewer.js","/viewer/viewer-core.js","/viewer/viewer-tiles.js","/viewer/viewer-editing.js","/viewer/viewer-crop.js","/viewer/viewer-export.js","/viewer/viewer-player.js").permitAll()
                        .requestMatchers("/api/v1/media", "/api/v1/media/**",
                                "/api/v1/viewer/sessions", "/api/v1/viewer/sessions/**",
                                "/api/v1/viewer/bootstrap","/api/v1/stamps","/api/v1/stamps/**").authenticated()
                        .anyRequest().denyAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        // sendError would trigger a container ERROR dispatch that can replace 403 with 401.
                        .accessDeniedHandler((request,response,failure)->{
                            response.setStatus(403);response.setHeader("Cache-Control","no-store");
                            response.setContentType("application/problem+json");
                            response.getWriter().write("{\"status\":403,\"code\":\"ACCESS_DENIED\",\"title\":\"Forbidden\"}");
                        }))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; img-src 'self' blob:; media-src 'self'; object-src 'none'; base-uri 'self'; "
                                        + "frame-ancestors 'self'")))
                .build();
    }

    @Bean
    co.kr.satu.imagegate.audit.config.AuditRequestFilter auditRequestFilter(co.kr.satu.imagegate.system.config.ImageGateProperties properties){
        return new co.kr.satu.imagegate.audit.config.AuditRequestFilter(properties.audit().enabled());
    }
    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<co.kr.satu.imagegate.audit.config.AuditRequestFilter> auditRegistration(co.kr.satu.imagegate.audit.config.AuditRequestFilter filter){
        var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration;
    }

    @Bean
    @ConditionalOnMissingBean(UserDetailsService.class)
    UserDetailsService rejectUnconfiguredAuthentication() {
        // Prevent creation of Spring Boot's generated development user/password.
        return username -> {
            throw new UsernameNotFoundException("Authentication integration is not configured");
        };
    }
}
