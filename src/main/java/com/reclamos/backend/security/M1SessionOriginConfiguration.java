package com.reclamos.backend.security;

import com.reclamos.backend.exception.InvalidSessionRequestException;
import com.reclamos.backend.identity.m1.M1Settings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Collections;

/** Cookie endpoints require an exact configured origin and a non-simple request header.
 * Bearer-only business APIs retain their existing stateless authorization policy. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "M1")
public class M1SessionOriginConfiguration implements WebMvcConfigurer, HandlerInterceptor {
    private final M1SessionCookies cookies;

    public M1SessionOriginConfiguration(M1SessionCookies cookies) { this.cookies = cookies; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/auth/login", "/api/auth/refresh", "/api/auth/logout");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) return true;
        var origins = Collections.list(request.getHeaders("Origin"));
        try {
            if (origins.size() != 1 || !cookies.browserOrigin().equals(M1Settings.httpsOrigin(origins.getFirst()).toString())) {
                throw new InvalidSessionRequestException();
            }
        } catch (IllegalStateException malformed) {
            throw new InvalidSessionRequestException();
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.equals("/api/auth/login") && !"1".equals(request.getHeader(M1SessionCookies.CSRF_HEADER))) {
            throw new InvalidSessionRequestException();
        }
        return true;
    }
}
