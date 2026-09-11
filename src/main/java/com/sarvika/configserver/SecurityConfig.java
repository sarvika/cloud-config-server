package com.sarvika.configserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Basic Auth guards every config-fetching endpoint, but health checks must stay
 * reachable without credentials - orchestrators (k8s liveness/readiness probes, load
 * balancer health checks, ...) don't carry this server's Basic Auth credential. CSRF is
 * disabled: this is a stateless, credential-per-request API with no browser/session
 * component, so CSRF protection (aimed at session-cookie-based browser flows) doesn't
 * apply and would otherwise block legitimate POSTs like /actuator/refresh.
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						.anyRequest().authenticated())
				.httpBasic(basic -> {
				})
				.csrf(csrf -> csrf.disable());
		return http.build();
	}
}
