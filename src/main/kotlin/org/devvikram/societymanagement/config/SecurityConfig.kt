package org.devvikram.societymanagement.config

import org.devvikram.societymanagement.security.JwtAuthFilter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.servlet.HandlerExceptionResolver

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
    // Filter-chain failures happen outside the DispatcherServlet, so @RestControllerAdvice never sees them.
    // Routing them through the resolver lets GlobalExceptionHandler render the same ErrorResponse body.
    @Qualifier("handlerExceptionResolver") private val exceptionResolver: HandlerExceptionResolver,
) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/api/auth/**").permitAll()
                it.anyRequest().authenticated()
            }
            .exceptionHandling {
                // Missing/invalid/expired token → 401 (the default Http403ForbiddenEntryPoint answered 403)
                it.authenticationEntryPoint { request, response, ex ->
                    exceptionResolver.resolveException(request, response, null, ex)
                }
                it.accessDeniedHandler { request, response, ex ->
                    exceptionResolver.resolveException(request, response, null, ex)
                }
            }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    @Bean
    fun authenticationManager(config: AuthenticationConfiguration): AuthenticationManager = config.authenticationManager
}
