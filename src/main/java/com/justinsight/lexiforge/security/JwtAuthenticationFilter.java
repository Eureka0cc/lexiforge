package com.justinsight.lexiforge.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final com.justinsight.lexiforge.repository.UserRepository users;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    JwtAuthenticationFilter(JwtService jwtService, com.justinsight.lexiforge.repository.UserRepository users,
                             org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.jwtService = jwtService;
        this.users = users;
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                var claims = jwtService.parse(header.substring(7));
                var user = users.findById(Long.parseLong(claims.getSubject())).orElseThrow();
                Number version = claims.get("sessionVersion", Number.class);
                if (user.sessionVersion != (version == null ? 0 : version.longValue())) throw new IllegalArgumentException();
                Number sessionId = claims.get("sid", Number.class);
                if (sessionId != null && jdbc.sql("SELECT count(*) FROM auth_session WHERE id=:id AND user_id=:uid AND revoked_at IS NULL AND expires_at>now()")
                        .param("id", sessionId.longValue()).param("uid", user.id).query(Long.class).single() != 1) throw new IllegalArgumentException();
                var auth = new UsernamePasswordAuthenticationToken(claims.getSubject(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(req, res);
    }
}
