package perfhub.security;

import perfhub.model.ApplicationUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class CurrentUserProvider {

    public Optional<Long> currentUserId() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return Optional.empty();
        }
        Object requestUser = request.getAttribute(AuthenticationInterceptor.CURRENT_USER_ATTRIBUTE);
        if (requestUser instanceof ApplicationUser user) {
            return Optional.of(user.id());
        }
        HttpSession session = request.getSession(false);
        Object sessionUserId = session == null ? null : session.getAttribute(SessionAttributes.CURRENT_USER_ID);
        if (sessionUserId instanceof Number userId) {
            return Optional.of(userId.longValue());
        }
        return Optional.empty();
    }

    private HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}
