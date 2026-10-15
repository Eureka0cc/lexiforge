package com.justinsight.lexiforge.security;

import com.justinsight.lexiforge.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;

/** Cookie 会话入口拒绝浏览器跨来源操作；命令行无 Origin 请求仍可用于本地调试。 */
@Component
public class AuthOriginFilter extends OncePerRequestFilter {
    private final String origin;
    private final ObjectMapper json = new ObjectMapper();
    public AuthOriginFilter(@Value("${app.frontend-origin}") String origin) { this.origin = origin; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("Origin");
        if (request.getMethod().equals("POST") && request.getRequestURI().startsWith("/api/auth/")
                && ((supplied != null && !supplied.equals(origin))
                || (supplied == null && "cross-site".equals(request.getHeader("Sec-Fetch-Site"))))) {
            response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getWriter(), ApiResponse.error("FORBIDDEN_ORIGIN", "请求来源不受信任", Map.of(), null));
            return;
        }
        chain.doFilter(request, response);
    }
}
