package org.devvikram.societymanagement.auth.security

import io.jsonwebtoken.JwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.devvikram.societymanagement.auth.service.UserDetailsServiceImpl
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
    private val userDetailsService: UserDetailsServiceImpl,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().authentication == null) {
            try {
                val phone = jwtService.extractPhone(header.substring(7))
                val userDetails = userDetailsService.loadUserByUsername(phone)
                // A deactivated user's still-unexpired token must stop working immediately.
                if (userDetails.isEnabled) {
                    val authToken = UsernamePasswordAuthenticationToken(userDetails, null, userDetails.authorities)
                    authToken.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = authToken
                }
            } catch (ex: JwtException) {
                // invalid/expired token — request proceeds unauthenticated; the entry point answers 401
            } catch (ex: UsernameNotFoundException) {
                // token for a user that no longer exists — same treatment as an invalid token
            }
        }
        filterChain.doFilter(request, response)
    }
}
