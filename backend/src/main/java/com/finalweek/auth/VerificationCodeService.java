package com.finalweek.auth;

import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;

@Service
public class VerificationCodeService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final MailSender mailSender;
    private final FinalWeekProperties properties;

    public VerificationCodeService(
            StringRedisTemplate redisTemplate,
            MailSender mailSender,
            FinalWeekProperties properties) {
        this.redisTemplate = redisTemplate;
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public Duration send(String email) {
        var normalizedEmail = normalize(email);
        var auth = properties.auth();
        var cooldownKey = "fw:auth:cooldown:" + normalizedEmail;
        var accepted = redisTemplate.opsForValue().setIfAbsent(cooldownKey, "1", auth.sendCooldown());
        if (!Boolean.TRUE.equals(accepted)) {
            var remaining = redisTemplate.getExpire(cooldownKey);
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "AUTH_CODE_RATE_LIMITED",
                    "请求过于频繁，请在 " + Math.max(1, remaining) + " 秒后重试");
        }

        var rateKey = "fw:auth:send-rate:" + normalizedEmail;
        var count = redisTemplate.opsForValue().increment(rateKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(rateKey, auth.rateWindow());
        }
        if (count != null && count > auth.maxSendsPerWindow()) {
            redisTemplate.delete(cooldownKey);
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "AUTH_CODE_RATE_LIMITED",
                    "验证码请求次数过多，请稍后再试");
        }

        var code = "%06d".formatted(SECURE_RANDOM.nextInt(1_000_000));
        redisTemplate.opsForValue().set(codeKey(normalizedEmail), code, auth.codeTtl());
        redisTemplate.delete(attemptKey(normalizedEmail));

        var message = new SimpleMailMessage();
        message.setFrom("noreply@finalweek.local");
        message.setTo(normalizedEmail);
        message.setSubject("FinalWeek 登录验证码");
        message.setText("你的 FinalWeek 登录验证码是：" + code + "\n\n验证码在 "
                + auth.codeTtl().toMinutes() + " 分钟内有效。请勿转发给他人。");
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            redisTemplate.delete(codeKey(normalizedEmail));
            redisTemplate.delete(cooldownKey);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_MAIL_UNAVAILABLE",
                    "验证码邮件暂时无法发送，请稍后重试");
        }
        return auth.sendCooldown();
    }

    public String verify(String email, String code) {
        var normalizedEmail = normalize(email);
        var stored = redisTemplate.opsForValue().get(codeKey(normalizedEmail));
        if (stored == null) {
            throw invalidCode();
        }

        var matches = MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            var attempts = redisTemplate.opsForValue().increment(attemptKey(normalizedEmail));
            if (attempts != null && attempts == 1L) {
                redisTemplate.expire(attemptKey(normalizedEmail), properties.auth().codeTtl());
            }
            if (attempts != null && attempts >= properties.auth().maxVerifyAttempts()) {
                redisTemplate.delete(codeKey(normalizedEmail));
            }
            throw invalidCode();
        }

        redisTemplate.delete(codeKey(normalizedEmail));
        redisTemplate.delete(attemptKey(normalizedEmail));
        return normalizedEmail;
    }

    private BusinessException invalidCode() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "AUTH_CODE_INVALID", "验证码错误或已过期");
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String codeKey(String email) {
        return "fw:auth:code:" + email;
    }

    private String attemptKey(String email) {
        return "fw:auth:verify-attempt:" + email;
    }
}
