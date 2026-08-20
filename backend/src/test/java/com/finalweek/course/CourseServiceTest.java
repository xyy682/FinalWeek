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
import com.finalweek.task.BackgroundTaskRepository;
import com.finalweek.task.BackgroundTask;
import com.finalweek.task.TaskStatus;
import com.finalweek.material.MaterialRepository;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.upload.ObjectStorage;
import com.finalweek.mockexam.MockExamCleanupService;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CourseServiceTest {

    private CourseRepository courseRepository;
    private UserAccountRepository userRepository;
    private CourseService service;
    private BackgroundTaskRepository tasks;
    private MockExamCleanupService mockExamCleanup;
    private MaterialRepository materials;

    @BeforeEach
    void setUp() {
        courseRepository = mock(CourseRepository.class);
        userRepository = mock(UserAccountRepository.class);
        var properties = new FinalWeekProperties(
                new FinalWeekProperties.Auth(Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofMinutes(10), 5, 5),
                new FinalWeekProperties.Limits(8, Duration.ofHours(24), 100, 2048, Duration.ofHours(2), 5, 30, 5, 20),
                new FinalWeekProperties.Retrieval(20, 20, 8, 60, 512, 64, 10, 1024,
                        "http://localhost:6333", "segments", java.nio.file.Path.of("build/lucene")),
                new FinalWeekProperties.Ai("https://example.com", "", 3, Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(60), 20,
                        "asr", "ocr", "embedding", "llm"));
        tasks = mock(BackgroundTaskRepository.class);
        mockExamCleanup = mock(MockExamCleanupService.class);
        materials = mock(MaterialRepository.class);
        service = new CourseService(courseRepository, userRepository, properties,
                tasks, materials,
                mock(CourseSegmentRepository.class), mock(ObjectStorage.class),
                mock(com.finalweek.knowledge.KnowledgeCleanupService.class), mockExamCleanup);
    }

    @Test
    void courseDeletionPreparesMockExamCleanupBeforeMaterialSegmentsAreRemoved() {
        var userId = UUID.randomUUID(); var courseId = UUID.randomUUID(); var course = mock(Course.class);
        when(courseRepository.findOwnedByIdForUpdate(courseId, userId)).thenReturn(Optional.of(course));
        when(tasks.lockAllByCourseId(courseId)).thenReturn(java.util.List.of());
        when(materials.findAllByCourse_IdAndDeletedFalse(courseId)).thenReturn(java.util.List.of());

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try { service.delete(userId, courseId); }
        finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }

        verify(mockExamCleanup).prepareCourseDeletion(courseId);
        verify(course).markDeleted();
        verify(courseRepository).save(course);
    }

    @Test
    void runningTaskPreventsCourseDeletionWithoutCancellingAnything() {
        var userId = UUID.randomUUID(); var courseId = UUID.randomUUID(); var course = mock(Course.class);
        var running = mock(BackgroundTask.class); when(running.getStatus()).thenReturn(TaskStatus.PROCESSING);
        when(courseRepository.findOwnedByIdForUpdate(courseId, userId)).thenReturn(Optional.of(course));
        when(tasks.lockAllByCourseId(courseId)).thenReturn(java.util.List.of(running));

        assertThatThrownBy(() -> service.delete(userId, courseId)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo("COURSE_TASK_RUNNING"));

        verify(tasks, org.mockito.Mockito.never()).cancelUnstarted(org.mockito.ArgumentMatchers.any());
        verify(courseRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        verify(mockExamCleanup, org.mockito.Mockito.never()).prepareCourseDeletion(courseId);
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
