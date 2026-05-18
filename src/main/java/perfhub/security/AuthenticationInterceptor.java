package perfhub.security;

import perfhub.model.ApplicationUser;
import perfhub.model.UserRole;
import perfhub.repository.ApplicationUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    public static final String CURRENT_USER_ATTRIBUTE = "currentUser";

    private final ApplicationUserRepository userRepository;

    public AuthenticationInterceptor(ApplicationUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws IOException {
        String path = request.getRequestURI();
        if (isPublicPath(path)) {
            return true;
        }

        ApplicationUser currentUser = resolveCurrentUser(request);
        if (currentUser == null) {
            redirectToLogin(request, response);
            return false;
        }
        request.setAttribute(CURRENT_USER_ATTRIBUTE, currentUser);

        if (isAdminPath(path) && currentUser.role() != UserRole.ADMIN) {
            denyAccess(request, response);
            return false;
        }
        return true;
    }

    private ApplicationUser resolveCurrentUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object userIdValue = session.getAttribute(SessionAttributes.CURRENT_USER_ID);
        if (!(userIdValue instanceof Number userId)) {
            return null;
        }
        return userRepository.findActiveById(userId.longValue())
                .orElseGet(() -> {
                    session.invalidate();
                    return null;
                });
    }

    private boolean isPublicPath(String path) {
        return path.equals("/login")
                || path.equals("/logout")
                || path.equals("/error")
                || path.equals("/favicon.png")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/img/")
                || path.startsWith("/webjars/");
    }

    private boolean isAdminPath(String path) {
        return path.equals("/admin")
                || path.startsWith("/admin/")
                || path.startsWith("/api/admin/");
    }

    private void redirectToLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isApiPath(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"message\":\"Требуется вход в личный кабинет\"}");
            return;
        }
        String redirect = request.getRequestURI();
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            redirect += "?" + query;
        }
        response.sendRedirect("/login?redirect=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8));
    }

    private void denyAccess(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isApiPath(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"message\":\"Недостаточно прав\"}");
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }

    private boolean isApiPath(String path) {
        return path.startsWith("/api/");
    }
}
