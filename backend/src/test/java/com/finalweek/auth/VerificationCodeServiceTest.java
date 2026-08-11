package com.finalweek.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

class VerificationCodeServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private MailSender mailSender;
    private VerificationCodeService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        mailSender = mock(MailSender.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new VerificationCodeService(redis, mailSender, properties());
    }

    @Test
    void sendsSixDigitCodeAndStoresItWithTtl() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(values.increment(anyString())).thenReturn(1L);
        var message = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);

        assertThat(service.send(" Student@Example.COM ")).isEqualTo(Duration.ofMinutes(1));

        verify(values).set(org.mockito.ArgumentMatchers.eq("fw:auth:code:student@example.com"),
                org.mockito.ArgumentMatchers.matches("\\d{6}"), org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(10)));
        verify(mailSender).send(message.capture());
        assertThat(message.getValue().getText()).containsPattern("\\d{6}");
    }

    @Test
    void rejectsRequestInsideCooldown() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(redis.getExpire(anyString())).thenReturn(42L);

        assertCode(() -> service.send("student@example.com"), "AUTH_CODE_RATE_LIMITED");
    }

    @Test
    void expiredCodeIsRejected() {
        when(values.get("fw:auth:code:student@example.com")).thenReturn(null);

        assertCode(() -> service.verify("student@example.com", "123456"), "AUTH_CODE_INVALID");
    }

    @Test
    void wrongCodeIsRejectedAndAttemptIsCounted() {
        when(values.get("fw:auth:code:student@example.com")).thenReturn("123456");
        when(values.increment("fw:auth:verify-attempt:student@example.com")).thenReturn(1L);

        assertCode(() -> service.verify("student@example.com", "654321"), "AUTH_CODE_INVALID");
        verify(redis).expire("fw:auth:verify-attempt:student@example.com", Duration.ofMinutes(10));
    }

    @Test
    void matchingCodeIsConsumed() {
        when(values.get("fw:auth:code:student@example.com")).thenReturn("123456");

        assertThat(service.verify("STUDENT@example.com", "123456")).isEqualTo("student@example.com");
        verify(redis).delete("fw:auth:code:student@example.com");
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private FinalWeekProperties properties() {
        return new FinalWeekProperties(
                new FinalWeekProperties.Auth(Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofMinutes(10), 5, 5),
                new FinalWeekProperties.Limits(8, Duration.ofHours(24), 100, 2048, Duration.ofHours(2), 5, 30, 5, 20),
                new FinalWeekProperties.Retrieval(20, 20, 8, 60, 512, 64, 10, 1024,
                        "http://localhost:6333", "segments", java.nio.file.Path.of("build/lucene")),
                new FinalWeekProperties.Ai("https://example.com", "", 3, Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(60), 20,
                        "asr", "ocr", "embedding", "llm"));
    }
}
