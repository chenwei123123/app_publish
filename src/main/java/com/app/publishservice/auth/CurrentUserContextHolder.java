package com.app.publishservice.auth;

import com.app.publishservice.filter.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

@Component
public class CurrentUserContextHolder {

    public Optional<JwtUserContext> getCurrentUser() {
        return Optional.ofNullable(currentRequest())
                .map(request -> request.getAttribute(JwtAuthenticationFilter.REQUEST_ATTRIBUTE_JWT_USER))
                .filter(JwtUserContext.class::isInstance)
                .map(JwtUserContext.class::cast);
    }

    public JwtUserContext requireCurrentUser() {
        return getCurrentUser().orElseThrow(() -> new IllegalStateException("Current authenticated user is missing"));
    }

    public Optional<String> getCurrentUserId() {
        return getCurrentUser().map(JwtUserContext::userId);
    }

    public Optional<String> getCurrentUsername() {
        return getCurrentUser().map(JwtUserContext::username);
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return servletRequestAttributes.getRequest();
        }
        return null;
    }
}
