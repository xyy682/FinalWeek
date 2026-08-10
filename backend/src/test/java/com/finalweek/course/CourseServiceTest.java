package com.finalweek.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finalweek.auth.UserAccount;
import com.finalweek.auth.UserAccountRepository;
import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CourseServiceTest {

    private CourseRepository courseRepository;
    private UserAccountRepository userRepository;
    private CourseService service;

    @BeforeEach
    void setUp() {
        courseRepository = mock(CourseRepository.class);
        userRepository = mock(UserAccountRepository.class);
        var properties = new FinalWeekProperties(
                new FinalWeekProperties.Auth(Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofMinutes(10), 5, 5),
                new FinalWeekProperties.Limits(8, Duration.ofHours(24), 100, 2048, Duration.ofHours(2), 5, 30, 5, 20),
                new FinalWeekProperties.Retrieval(20, 20, 8, 60),
                new FinalWeekProperties.Ai(Duration.ofSeconds(60), Duration.ofSeconds(60), 20,
                        "asr", "ocr", "embedding", "llm"));
        service = new CourseService(courseRepository, userRepository, properties);
    }

    @Test
    void rejectsNinthActiveCourse() {
        var userId = UUID.randomUUID();
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(new UserAccount("student@example.com")));
        when(courseRepository.countByUserIdAndDeletedFalse(userId)).thenReturn(8L);

        assertThatThrownBy(() -> service.create(userId, "第九门课"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("COURSE_LIMIT_REACHED"));
    }

    @Test
    void normalizesCourseNameBeforeSaving() {
        var userId = UUID.randomUUID();
        var user = new UserAccount("student@example.com");
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(courseRepository.countByUserIdAndDeletedFalse(userId)).thenReturn(0L);
        when(courseRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

        var course = service.create(userId, "  数据库   系统  ");

        assertThat(course.getName()).isEqualTo("数据库 系统");
        verify(courseRepository).save(course);
    }
}

