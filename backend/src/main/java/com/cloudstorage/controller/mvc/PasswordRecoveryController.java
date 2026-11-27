package com.cloudstorage.controller.mvc;

import com.cloudstorage.dto.AuthRequests.ForgotPasswordRequest;
import com.cloudstorage.dto.AuthRequests.ResetPasswordRequest;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.service.PasswordResetService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PasswordRecoveryController {
    private final PasswordResetService recovery;
    private final Validator validator;
    private final MessageSource messages;

    @Autowired
    public PasswordRecoveryController(PasswordResetService recovery, Validator validator, MessageSource messages) {
        this.recovery = recovery;
        this.validator = validator;
        this.messages = messages;
    }

    @ModelAttribute
    void preventLinkLeakage(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @GetMapping("/forgot-password")
    public String forgot() {
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String request(@RequestParam(defaultValue = "") String email, Locale locale, Model model,
                          RedirectAttributes redirect, HttpServletResponse response) {
        ForgotPasswordRequest form = new ForgotPasswordRequest(email.strip());
        List<String> errors = validator.validate(form).stream().map(ConstraintViolation::getMessage)
                .distinct().sorted().toList();
        if (!errors.isEmpty()) {
            response.setStatus(400);
            model.addAttribute("formErrors", errors);
            model.addAttribute("email", form.email());
            return "forgot-password";
        }
        recovery.request(form.email(), locale);
        redirect.addFlashAttribute("recoveryRequested", true);
        return "redirect:/forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPage(@RequestParam(required = false) String token, Model model,
                            HttpServletResponse response) {
        return resetForm(recovery.isValid(token) ? token : null, List.of(), model, response);
    }

    @PostMapping("/reset-password")
    public String reset(@RequestParam(required = false) String token,
                        @RequestParam(defaultValue = "") String password,
                        @RequestParam(defaultValue = "") String confirmation,
                        Model model, RedirectAttributes redirect, HttpServletResponse response) {
        try {
            if (!recovery.reset(token, new ResetPasswordRequest(password, confirmation))) {
                return resetForm(null, List.of(), model, response);
            }
        } catch (ConstraintViolationException exception) {
            List<String> errors = exception.getConstraintViolations().stream().map(ConstraintViolation::getMessage)
                    .distinct().sorted().toList();
            return resetForm(token, errors, model, response);
        } catch (ApiException exception) {
            return resetForm(token, List.of(exception.resolveMessage(messages)), model, response);
        }
        redirect.addFlashAttribute("notice", "auth.reset.success");
        return "redirect:/login";
    }

    private String resetForm(String token, List<String> errors, Model model, HttpServletResponse response) {
        if (token == null || !errors.isEmpty()) {
            response.setStatus(400);
        }
        model.addAttribute("resetToken", token);
        model.addAttribute("formErrors", errors);
        return "reset-password";
    }
}
