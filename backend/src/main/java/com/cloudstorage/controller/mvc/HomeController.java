package com.cloudstorage.controller.mvc;

import com.cloudstorage.dto.AuthRequests.LoginRequest;
import com.cloudstorage.dto.AuthRequests.RegisterRequest;
import com.cloudstorage.dto.FolderContentsResponse;
import com.cloudstorage.dto.FolderResponse;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.security.UserPrincipal;
import com.cloudstorage.service.AuthService;
import com.cloudstorage.service.FileService;
import com.cloudstorage.service.FolderService;
import com.cloudstorage.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class HomeController {
    private final AuthService accounts;
    private final SessionService sessions;
    private final FolderService folders;
    private final FileService files;
    private final Validator validator;

    @Autowired
    public HomeController(AuthService accounts, SessionService sessions, FolderService folders,
                          FileService files, Validator validator) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.folders = folders;
        this.files = files;
        this.validator = validator;
    }

    @GetMapping("/")
    public String home(@AuthenticationPrincipal UserPrincipal user, Model model) {
        if (user != null) {
            return workspace(user, null, model);
        }
        model.addAttribute("registering", false);
        return "index";
    }

    @GetMapping("/index.html")
    public String legacyHome() {
        return "redirect:/";
    }

    @GetMapping({"/login", "/register"})
    public String authentication(@AuthenticationPrincipal UserPrincipal user, HttpServletRequest request, Model model) {
        if (user != null) {
            return "redirect:/files";
        }
        model.addAttribute("registering", request.getRequestURI().equals(request.getContextPath() + "/register"));
        return "index";
    }

    @PostMapping({"/login", "/register"})
    public String authenticate(@RequestParam(defaultValue = "") String email,
                               @RequestParam(defaultValue = "") String password,
                               HttpServletRequest request, HttpServletResponse response,
                               Model model, RedirectAttributes redirect) {
        boolean registering = request.getRequestURI().equals(request.getContextPath() + "/register");
        email = email.strip();
        Object credentials = registering ? new RegisterRequest(email, password) : new LoginRequest(email, password);
        if (!validator.validate(credentials).isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return authError(model, registering, email, registering
                    ? "The request is invalid or incomplete. Check the email address and use a password from 12 to 128 characters."
                    : "The request is invalid or incomplete.");
        }
        try {
            if (registering) {
                accounts.register(email, password);
            }
            sessions.login(email, password, request, response);
        } catch (ApiException exception) {
            response.setStatus(exception.status().value());
            return authError(model, registering, email, exception.getMessage());
        }
        redirect.addFlashAttribute("notice", registering ? "Your account is ready." : "Welcome back.");
        return "redirect:/files";
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response,
                         Authentication authentication, RedirectAttributes redirect) {
        sessions.logout(request, response, authentication);
        redirect.addFlashAttribute("notice", "You’ve been signed out.");
        return "redirect:/login";
    }

    @GetMapping({"/files", "/folders/{folderId}"})
    public String workspace(@AuthenticationPrincipal UserPrincipal user,
                            @PathVariable(required = false) UUID folderId, Model model) {
        UUID ownerId = user.id();
        FolderContentsResponse contents = folderId == null
                ? new FolderContentsResponse(null, folders.list(ownerId, null), files.list(ownerId, null))
                : folders.contents(folderId, ownerId);
        List<FolderResponse> breadcrumbs = new ArrayList<>();
        for (FolderResponse folder = contents.folder(); folder != null;
                folder = folder.parentId() == null ? null : folders.get(folder.parentId(), ownerId)) {
            breadcrumbs.add(folder);
        }
        Collections.reverse(breadcrumbs);

        model.addAttribute("currentUser", accounts.accountDetails(ownerId));
        model.addAttribute("currentFolder", contents.folder());
        model.addAttribute("breadcrumbs", breadcrumbs);
        model.addAttribute("folders", contents.folders());
        model.addAttribute("files", contents.files());
        model.addAttribute("itemCount", contents.folders().size() + contents.files().size());
        return "index";
    }

    private String authError(Model model, boolean registering, String email, String message) {
        model.addAttribute("registering", registering);
        model.addAttribute("email", email);
        model.addAttribute("formError", message);
        return "index";
    }
}
