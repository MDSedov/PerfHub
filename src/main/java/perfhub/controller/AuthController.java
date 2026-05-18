package com.example.chronicler.controller;

import com.example.chronicler.model.ApplicationUser;
import com.example.chronicler.security.SessionAttributes;
import com.example.chronicler.service.ApplicationUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    private final ApplicationUserService userService;

    public AuthController(ApplicationUserService userService) {
        this.userService = userService;
    }

    @GetMapping("/login")
    public String login(
            @RequestParam(value = "redirect", required = false) String redirect,
            HttpServletRequest request,
            Model model) {
        if (isAuthenticated(request)) {
            return "redirect:" + normalizeRedirect(redirect);
        }
        model.addAttribute("redirect", normalizeRedirect(redirect));
        return "login";
    }

    @PostMapping("/login")
    public String authenticate(
            @RequestParam("username") String username,
            @RequestParam("password") String password,
            @RequestParam(value = "redirect", required = false) String redirect,
            HttpServletRequest request,
            Model model) {
        return userService.authenticate(username, password)
                .map(user -> loginUser(request, user, redirect))
                .orElseGet(() -> {
                    model.addAttribute("loginError", "Неверное имя пользователя или пароль");
                    model.addAttribute("username", username);
                    model.addAttribute("redirect", normalizeRedirect(redirect));
                    return "login";
                });
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/login";
    }

    private String loginUser(HttpServletRequest request, ApplicationUser user, String redirect) {
        HttpSession session = request.getSession(true);
        session.setAttribute(SessionAttributes.CURRENT_USER_ID, user.id());
        return "redirect:" + normalizeRedirect(redirect);
    }

    private boolean isAuthenticated(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(SessionAttributes.CURRENT_USER_ID) instanceof Number;
    }

    private String normalizeRedirect(String redirect) {
        if (!StringUtils.hasText(redirect) || !redirect.startsWith("/") || redirect.startsWith("//")) {
            return "/main";
        }
        if (redirect.startsWith("/login")) {
            return "/main";
        }
        return redirect;
    }
}
