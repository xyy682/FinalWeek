package com.finalweek.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final VerificationCodeService codeService;
    private final UserAccountService userAccountService;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(
            VerificationCodeService codeService,
            UserAccountService userAccountService,
            SecurityContextRepository securityContextRepository) {
        this.codeService = codeService;
        this.userAccountService = userAccountService;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/auth/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CodeSentResponse sendCode(@Valid @RequestBody SendCodeRequest request) {
        var cooldown = codeService.send(request.email());
        return new CodeSentResponse(cooldown.toSeconds(), "验证码已发送，请在 Mailpit 中查看");
    }

    @PostMapping("/auth/login")
    UserResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        var email = codeService.verify(request.email(), request.code());
        var user = userAccountService.findOrCreate(email);
        var principal = new FinalWeekPrincipal(user.getId(), user.getEmail());
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, java.util.List.of());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, servletRequest, servletResponse);
        return UserResponse.from(principal);
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    UserResponse me(@AuthenticationPrincipal FinalWeekPrincipal principal) {
        return UserResponse.from(principal);
    }

    record SendCodeRequest(@NotBlank @Email String email) {}

    record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "必须是 6 位数字") String code) {}

    record CodeSentResponse(long retryAfterSeconds, String message) {}

    public record UserResponse(java.util.UUID id, String email) {
        static UserResponse from(FinalWeekPrincipal principal) {
            return new UserResponse(principal.userId(), principal.email());
        }
    }
}

