package com.example.chronicler.controller.advice;

import com.example.chronicler.model.ApplicationUser;
import com.example.chronicler.repository.ApplicationUserRepository;
import com.example.chronicler.security.AuthenticationInterceptor;
import com.example.chronicler.security.SessionAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class NavigationModelAdvice {

    private final ApplicationUserRepository userRepository;

    public NavigationModelAdvice(ApplicationUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @ModelAttribute("currentUser")
    public ApplicationUser currentUser(HttpServletRequest request) {
        return resolveCurrentUser(request);
    }

    @ModelAttribute("currentUserFullName")
    public String currentUserFullName(HttpServletRequest request) {
        ApplicationUser currentUser = resolveCurrentUser(request);
        return currentUser == null ? "Пользователь" : currentUser.fullName();
    }

    @ModelAttribute("currentUserAdmin")
    public boolean currentUserAdmin(HttpServletRequest request) {
        ApplicationUser currentUser = resolveCurrentUser(request);
        return currentUser != null && currentUser.admin();
    }

    @ModelAttribute("activeAdminSection")
    public String activeAdminSection() {
        return null;
    }

    private ApplicationUser resolveCurrentUser(HttpServletRequest request) {
        Object requestUser = request.getAttribute(AuthenticationInterceptor.CURRENT_USER_ATTRIBUTE);
        if (requestUser instanceof ApplicationUser user) {
            return user;
        }
        HttpSession session = request.getSession(false);
        Object userId = session == null ? null : session.getAttribute(SessionAttributes.CURRENT_USER_ID);
        if (userId instanceof Number id) {
            ApplicationUser user = userRepository.findActiveById(id.longValue()).orElse(null);
            if (user != null) {
                request.setAttribute(AuthenticationInterceptor.CURRENT_USER_ATTRIBUTE, user);
            }
            return user;
        }
        return null;
    }
}
