package com.cloudstorage.controller.mvc;

import com.cloudstorage.dto.AuthRequests.ResetPasswordRequest;
import com.cloudstorage.dto.ProfileRequests.ChangeEmailRequest;
import com.cloudstorage.dto.ProfileRequests.ChangePasswordRequest;
import com.cloudstorage.dto.ProfileRequests.UpdateProfileRequest;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.security.UserPrincipal;
import com.cloudstorage.service.SessionService;
import com.cloudstorage.service.UserProfileService;
import com.cloudstorage.service.UserProfileService.Confirmation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/profile")
public class UserProfileController {
    private static final String PENDING_EMAIL = UserProfileController.class.getName() + ".pendingEmail";

    private final UserProfileService profiles;
    private final SessionService sessions;
    private final MessageSource messages;

    @Autowired
    public UserProfileController(UserProfileService profiles, SessionService sessions, MessageSource messages) {
        this.profiles = profiles;
        this.sessions = sessions;
        this.messages = messages;
    }

    @ModelAttribute
    void privacyHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @GetMapping
    public String profile(@AuthenticationPrincipal UserPrincipal user, Model model) {
        model.addAttribute("profile", profiles.getProfile(user));
        return "users/profile";
    }

    @PostMapping
    public String updateName(@AuthenticationPrincipal UserPrincipal user,
                             @RequestParam(defaultValue = "") String displayName,
                             RedirectAttributes redirect, Model model, HttpServletResponse response) {
        return update(user, model, response, () -> profiles.updateDisplayName(user, new UpdateProfileRequest(displayName)),
                () -> {
                    redirect.addFlashAttribute("notice", "profile.updated");
                    return "redirect:/profile";
                });
    }

    @PostMapping("/email")
    public String requestEmailChange(@AuthenticationPrincipal UserPrincipal user,
                                     @RequestParam(defaultValue = "") String newEmail,
                                     @RequestParam(defaultValue = "") String currentPassword,
                                     Locale locale, RedirectAttributes redirect, Model model, HttpServletResponse response) {
        return update(user, model, response,
                () -> profiles.requestEmailChange(user, new ChangeEmailRequest(newEmail.strip(), currentPassword), locale),
                () -> {
                    redirect.addFlashAttribute("notice", "profile.email.sent");
                    return "redirect:/profile";
                });
    }

    @GetMapping("/email/confirm")
    public String emailConfirmation(@AuthenticationPrincipal UserPrincipal user,
                                    @RequestParam(required = false) String token, HttpSession session,
                                    Model model, HttpServletResponse response) {
        var confirmation = profiles.prepareConfirmation(user, token);
        if (confirmation.isEmpty()) {
            return invalidConfirmation(model, response);
        }
        PendingConfirmation pending = new PendingConfirmation(UUID.randomUUID(), confirmation.orElseThrow());
        session.setAttribute(PENDING_EMAIL, pending);
        model.addAttribute("confirmationId", pending.id());
        model.addAttribute("newEmail", pending.confirmation().email());
        return "users/confirm-email";
    }

    @PostMapping("/email/confirm")
    public String confirmEmail(@AuthenticationPrincipal UserPrincipal user, Authentication authentication,
                               @RequestParam(defaultValue = "") String confirmationId, Locale locale,
                               HttpServletRequest request, HttpServletResponse response, HttpSession session,
                               Model model, RedirectAttributes redirect) {
        if (!(session.getAttribute(PENDING_EMAIL) instanceof PendingConfirmation pending)
                || !pending.id().toString().equals(confirmationId)) {
            return invalidConfirmation(model, response);
        }
        session.removeAttribute(PENDING_EMAIL);
        try {
            profiles.confirmEmailChange(user, pending.confirmation(), locale);
        } catch (ApiException exception) {
            response.setStatus(exception.status().value());
            model.addAttribute("formErrors", List.of(exception.resolveMessage(messages)));
            return "users/confirm-email";
        }
        sessions.logout(request, response, authentication);
        redirect.addFlashAttribute("notice", "profile.email.confirmed");
        return "redirect:/login";
    }

    @PostMapping("/password")
    public String changePassword(@AuthenticationPrincipal UserPrincipal user, Authentication authentication,
                                 @RequestParam(defaultValue = "") String currentPassword,
                                 @RequestParam(defaultValue = "") String newPassword,
                                 @RequestParam(defaultValue = "") String confirmation, HttpServletRequest request,
                                 HttpServletResponse response, Model model, RedirectAttributes redirect) {
        return update(user, model, response, () -> profiles.changePassword(user,
                new ChangePasswordRequest(currentPassword, new ResetPasswordRequest(newPassword, confirmation))),
                () -> {
                    sessions.logout(request, response, authentication);
                    redirect.addFlashAttribute("notice", "profile.password.updated");
                    return "redirect:/login";
                });
    }

    private String update(UserPrincipal user, Model model, HttpServletResponse response,
                          Runnable action, java.util.function.Supplier<String> success) {
        try {
            action.run();
            return success.get();
        } catch (ConstraintViolationException exception) {
            response.setStatus(400);
            model.addAttribute("formErrors", exception.getConstraintViolations().stream()
                    .map(ConstraintViolation::getMessage).distinct().sorted().toList());
        } catch (ApiException exception) {
            response.setStatus(exception.status().value());
            model.addAttribute("formErrors", List.of(exception.resolveMessage(messages)));
        }
        return profile(user, model);
    }

    private String invalidConfirmation(Model model, HttpServletResponse response) {
        response.setStatus(400);
        model.addAttribute("invalidConfirmation", true);
        return "users/confirm-email";
    }

    private record PendingConfirmation(UUID id, Confirmation confirmation) {}
}
