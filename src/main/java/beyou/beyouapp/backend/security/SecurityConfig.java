package beyou.beyouapp.backend.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.DispatcherType;


@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Autowired
    SecurityFilter securityFilter;

    @Autowired
    DocsImportSecretFilter docsImportSecretFilter;

    @Value("${cors.allowed-pattern}")
    private String allowedOrigin;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // ASYNC dispatch (SseEmitter re-dispatch for streaming) is permitted:
                        // DispatcherType.ASYNC is set only by the container on re-dispatch, never
                        // by a client, and SecurityFilter (OncePerRequestFilter) skips async
                        // dispatch. INVARIANT: every protected endpoint must authenticate + run its
                        // ownership check on the INITIAL REQUEST dispatch (the agent stream does, via
                        // getChat(chatId, userId)) — anything relying on auth during async re-dispatch
                        // would be silently permitted here.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(
                            "/auth/login",
                            "/auth/register",
                            "/auth/google",
                            "/auth/google/mobile",
                            "/auth/refresh",
                            "/auth/logout",
                            "/auth/forgot-password",
                            "/auth/reset-password/**",
                            "/auth/verify-email",
                            "/auth/resend-verification",
                            // An unsubscribe link has to work for someone who cannot log
                            // in — that is what makes it an unsubscribe link rather than a
                            // settings screen. The token in the request body is the whole
                            // proof of ownership, and RateLimitFilter bounds this path per
                            // address because an unauthenticated write is otherwise
                            // unthrottled: every other branch of that filter needs a user
                            // id and lets the request through when there is none.
                            "/notification/unsubscribe"
                        ).permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/docs/admin/**").authenticated()
                        .requestMatchers("/docs/**").permitAll()
                        // Outside the JWT filter on purpose: the callers are an <img src>
                        // and an <Image uri>, which cannot send a header. Authorization
                        // rides in the query string instead — UserPhotoController refuses
                        // anything without a live signature from PhotoUrlSigner.
                        .requestMatchers(HttpMethod.GET, "/user/photo/**").permitAll()
                        // Admin console. The ADMIN role is granted only by a manual
                        // database UPDATE — no code path assigns it.
                        .requestMatchers("/feedback/admin/**").hasRole("ADMIN")
                        .anyRequest()
                        .authenticated()
                )
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data:; connect-src 'self' https://accounts.google.com https://www.googleapis.com; font-src 'self' https: data:; frame-ancestors 'none'")
                        )
                        .referrerPolicy(referrer -> referrer
                                .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                        )
                        .permissionsPolicyHeader(permissions -> permissions
                                .policy("camera=(), microphone=(), geolocation=()")
                        )
                )
                .addFilterBefore(securityFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(docsImportSecretFilter, UsernamePasswordAuthenticationFilter.class)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()));

        return http.build();
    }

    private UrlBasedCorsConfigurationSource corsConfigurationSource(){
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowCredentials(true);
        config.addAllowedOriginPattern(allowedOrigin);
        config.addAllowedHeader("*");
        config.addExposedHeader("X-Access-Token");
        // Retry-After is not on the CORS safelist, so without this the browser cannot
        // read it even though the filter always sends it on a 429 — a web client had
        // no way to say how long the wait is. X-Rate-Limit-Remaining rides along so a
        // client can warn before it hits the wall rather than only after.
        config.addExposedHeader("Retry-After");
        config.addExposedHeader("X-Rate-Limit-Remaining");
        config.addAllowedMethod("*");
        config.addAllowedMethod("GET");
        config.addAllowedMethod("POST");
        config.addAllowedMethod("PUT");
        config.addAllowedMethod("DELETE");
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder(12);
    }
}
