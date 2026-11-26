package com.cloudstorage.controller.mvc;

import com.cloudstorage.security.UserPrincipal;
import com.cloudstorage.service.AuthService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
    private final AuthService accounts;

    public HomeController(AuthService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/")
    public String home(@AuthenticationPrincipal UserPrincipal user, Model model) {
        if (user != null) {
            model.addAttribute("currentUser", accounts.accountDetails(user.id()));
        }
        return "forward:/index.html";
    }
}
