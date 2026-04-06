package dev.bytebank.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytebank.common.BytebankProperties;
import dev.bytebank.common.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] ALWAYS_PUBLIC = {
            "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, BytebankProperties properties, JwtVerifier verifier,
                                            ObjectMapper objectMapper) throws Exception {
        String[] publicPaths = properties.security().publicPaths().toArray(String[]::new);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(ALWAYS_PUBLIC).permitAll();
                    if (publicPaths.length > 0) {
                        auth.requestMatchers(publicPaths).permitAll();
                    }
                    auth.requestMatchers("/internal/**").hasRole("INTERNAL");
                    auth.anyRequest().hasAnyRole(Role.CLIENTE.name(), Role.GESTOR.name());
                })
                .addFilterBefore(new InternalKeyFilter(properties.internalKey()), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtAuthenticationFilter(verifier), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> write(res, req, objectMapper,
                                HttpStatus.UNAUTHORIZED, "NO_AUTENTICADO", "Hace falta un token válido"))
                        .accessDeniedHandler((req, res, ex) -> write(res, req, objectMapper,
                                HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "No tienes permiso para esta operación")))
                .build();
    }

    private static void write(HttpServletResponse response, HttpServletRequest request, ObjectMapper objectMapper,
                              HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(status, code, message, request.getRequestURI()));
    }
}
