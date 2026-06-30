package com.api_rate_limiter.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * V5: 요청 파이프라인의 정문. Authorization 헤더에서 API Key를 추출해 다음 계층으로 전달한다.
 * 인증 실패 시 비즈니스 로직에 도달하기 전에 401로 차단한다.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String API_KEY_ATTRIBUTE = "apiKey";

    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response);
            return;
        }

        String apiKey = authorization.substring(BEARER_PREFIX.length()).trim();
        if (apiKey.isEmpty()) {
            writeUnauthorized(response);
            return;
        }

        request.setAttribute(API_KEY_ATTRIBUTE, apiKey);
        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
    }
}
