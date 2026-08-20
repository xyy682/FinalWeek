package com.finalweek.knowledgeversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.finalweek.auth.UserAccount;
import com.finalweek.auth.UserAccountRepository;
import com.finalweek.common.api.BusinessException;
import com.finalweek.course.Course;
import com.finalweek.course.CourseRepository;
import com.finalweek.material.*;
import com.finalweek.mockexam.*;
import com.finalweek.outline.*;
import com.finalweek.task.*;
import com.finalweek.upload.UploadStateStore;
import com.finalweek.upload.ObjectStorage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeVersionJpaIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("finalweek_jpa").withUsername("finalweek").withPassword("finalweek_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired UserAccountRepository users;
    @Autowired CourseRepository courses;
    @Autowired MaterialRepository materials;
    @Autowired CourseKnowledgeVersionRepository versions;
    @Autowired CourseKnowledgeVersionMaterialRepository versionMaterials;
    @Autowired OutlineRepository outlines;
    @Autowired MockExamRepository exams;
    @Autowired MockExamObjectCleanupRepository examCleanups;
    @Autowired JdbcTemplate jdbc;
    @Autowired BackgroundTaskRepository tasks;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void persistsVersionSnapshotAndVersionedOutlineAgainstMigratedSchema() {
        var user = users.saveAndFlush(new UserAccount("version-jpa@example.com"));
        var course = courses.saveAndFlush(new Course(user, "数据结构"));
        var materialId = UUID.randomUUID();
        materials.saveAndFlush(new Material(materialId, course, "notes.md", "materials/notes.md",
                "a".repeat(64), 12, "text/markdown", MaterialType.NOTES, null));
        var version = versions.saveAndFlush(new CourseKnowledgeVersion(course, 1, "b".repeat(64)));

        versionMaterials.saveAndFlush(new CourseKnowledgeVersionMaterial(version.getId(), materialId, 0));
        var outline = outlines.saveAndFlush(new Outline(course, version, 1));

        assertThat(versionMaterials.findAllByKnowledgeVersionIdOrderByPosition(version.getId()))
                .extracting(CourseKnowledgeVersionMaterial::getMaterialId).containsExactly(materialId);
        assertThat(outlines.findByKnowledgeVersion_Id(version.getId())).contains(outline);
    }

    @Test
    void hardDeletingCleanedExamDetachesRetriesAndCascadesQuestions() {
        var user = users.saveAndFlush(new UserAccount("cleanup-jpa@example.com"));
        var course = courses.saveAndFlush(new Course(user, "工程力学"));
        var version = versions.saveAndFlush(new CourseKnowledgeVersion(course, 1, "c".repeat(64)));
        var request = new MockExamRequestNormalizer.Normalized(MockExamScope.WHOLE_COURSE, List.of(),
                Map.of(MockExamQuestionType.SINGLE_CHOICE, 1), ScoreMode.AUTO, Map.of(),
                null, null, true, "", 1, 2);
        var original = exams.saveAndFlush(new MockExam(user.getId(), course.getId(), version, null,
                "original", "模拟卷", "模拟卷", "{}", "d".repeat(64), request,
                "v1", .82, "v1", "v1"));
        var retry = exams.saveAndFlush(new MockExam(user.getId(), course.getId(), version, original,
                "retry", "重试卷", "模拟卷", "{}", "e".repeat(64), request,
                "v1", .82, "v1", "v1"));
        jdbc.update("insert into mock_exam_question " +
                        "(id,mock_exam_id,position,section_position,question_type,stem,answer_json,score,uses_general_knowledge,formula_metadata_json) " +
                        "values (UUID_TO_BIN(?),UUID_TO_BIN(?),1,1,'SINGLE_CHOICE','题干','{\"correctOptions\":[\"A\"]}',2,false,'[]')",
                UUID.randomUUID().toString(), original.getId().toString());
        original.delete(); exams.saveAndFlush(original);
        var cleanup = examCleanups.saveAndFlush(new MockExamObjectCleanup(original, "paper", "answer"));

        assertThat(exams.detachRetriesOf(original.getId())).isEqualTo(1);
        examCleanups.delete(cleanup); examCleanups.flush();
        exams.delete(original); exams.flush();

        assertThat(exams.findById(original.getId())).isEmpty();
        assertThat(exams.findById(retry.getId())).get().extracting(MockExam::getRetryOfId).isNull();
        assertThat(jdbc.queryForObject("select count(*) from mock_exam_question where mock_exam_id=UUID_TO_BIN(?)",
                Integer.class, original.getId().toString())).isZero();
    }

    @Test
    void completedPartialCleanupCanBeRequeuedWhenFailureHistoryIsDeletedLater() {
        var user = users.saveAndFlush(new UserAccount("cleanup-requeue-jpa@example.com"));
        var course = courses.saveAndFlush(new Course(user, "概率论"));
        var version = versions.saveAndFlush(new CourseKnowledgeVersion(course, 1, "9".repeat(64)));
        var request = new MockExamRequestNormalizer.Normalized(MockExamScope.WHOLE_COURSE, List.of(),
                Map.of(MockExamQuestionType.SINGLE_CHOICE, 1), ScoreMode.AUTO, Map.of(),
                null, null, true, "", 1, 2);
        var exam = exams.saveAndFlush(new MockExam(user.getId(), course.getId(), version, null,
                "cleanup-requeue", "失败记录", "模拟卷", "{}", "8".repeat(64), request,
                "v1", .82, "v1", "v1"));
        var cleanup = new MockExamObjectCleanup(exam, "partial-paper", "partial-answer");
        cleanup.succeed();
        cleanup = examCleanups.saveAndFlush(cleanup);
        exam.delete(); exams.saveAndFlush(exam);
        var properties = new MockExamProperties(3, Duration.ofMinutes(4), 50, 1000, 300,
                2000, 10, .82, "v1", 8);
        var service = new MockExamCleanupService(examCleanups, mock(ObjectStorage.class), exams,
                mock(MockExamQuestionSourceRepository.class), transactionManager, properties);

        service.enqueue(exam);
        examCleanups.flush();

        var requeued = examCleanups.findById(cleanup.getId()).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(MockExamCleanupStatus.PENDING);
        assertThat(requeued.getAttemptCount()).isZero();
        assertThat(requeued.getPaperObjectKey()).isEqualTo("partial-paper");
        assertThat(requeued.getAnswerObjectKey()).isEqualTo("partial-answer");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentConfirmationCreatesOneVersionAndDispatchesOneOutlineTask() throws Exception {
        var user = users.saveAndFlush(new UserAccount("confirm-race@example.com"));
        var course = courses.saveAndFlush(new Course(user, "操作系统"));
        var material = materials.saveAndFlush(new Material(UUID.randomUUID(), course, "race.md",
                "materials/race.md", "f".repeat(64), 16, "text/markdown", MaterialType.NOTES, null));
        jdbc.update("update material set status='SUCCEEDED' where id=UUID_TO_BIN(?)", material.getId().toString());
        var outlineTasks = mock(OutlineTaskFactory.class);
        var dispatcher = mock(TaskDispatchService.class);
        var uploads = mock(UploadStateStore.class);
        var task = mock(BackgroundTask.class); var taskId = UUID.randomUUID();
        when(task.getId()).thenReturn(taskId);
        when(outlineTasks.create(eq(user.getId()), eq(course.getId()), any(UUID.class), eq(1L)))
                .thenReturn(new OutlineTaskFactory.CreatedTask(task, true));
        var service = new KnowledgeVersionService(courses, materials, tasks, versions, versionMaterials,
                outlineTasks, dispatcher, uploads, transactionManager);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var calls = List.of(CompletableFuture.supplyAsync(() -> confirmAfter(start, service, user.getId(), course.getId()), executor),
                    CompletableFuture.supplyAsync(() -> confirmAfter(start, service, user.getId(), course.getId()), executor));
            start.countDown();
            var outcomes = calls.stream().map(CompletableFuture::join).toList();

            assertThat(outcomes).containsExactlyInAnyOrder("SUCCEEDED", "KNOWLEDGE_VERSION_UNCHANGED");
        }
        assertThat(versions.findFirstByCourse_IdOrderByVersionDesc(course.getId())).isPresent();
        verify(outlineTasks, times(1)).create(eq(user.getId()), eq(course.getId()), any(UUID.class), eq(1L));
        verify(dispatcher, times(1)).dispatch(task);
    }

    private String confirmAfter(CountDownLatch start, KnowledgeVersionService service, UUID userId, UUID courseId) {
        try {
            start.await(); service.confirm(userId, courseId, false); return "SUCCEEDED";
        } catch (BusinessException exception) { return exception.code(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new RuntimeException(exception); }
    }
}
