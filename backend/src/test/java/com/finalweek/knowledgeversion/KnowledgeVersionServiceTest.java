package com.finalweek.knowledgeversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.finalweek.common.api.BusinessException;
import com.finalweek.course.Course;
import com.finalweek.course.CourseRepository;
import com.finalweek.material.*;
import com.finalweek.outline.OutlineTaskFactory;
import com.finalweek.task.*;
import com.finalweek.upload.UploadStateStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class KnowledgeVersionServiceTest {
    @Test
    void refusesConfirmationWhileMaterialTaskIsActive() {
        var fixture = fixture();
        var active = mock(BackgroundTask.class);
        when(active.getTaskType()).thenReturn(TaskType.PARSE_MATERIAL);
        when(active.getStatus()).thenReturn(TaskStatus.PROCESSING);
        when(fixture.tasks.lockAllByCourseId(fixture.courseId)).thenReturn(List.of(active));

        assertThatThrownBy(() -> fixture.service.confirm(fixture.userId, fixture.courseId, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("KNOWLEDGE_VERSION_MATERIALS_BUSY"));
        verifyNoInteractions(fixture.versions, fixture.dispatcher);
    }

    @Test
    void requiresExplicitConfirmationBeforeIgnoringFailedMaterials() {
        var fixture = fixture();
        var success = material(MaterialStatus.SUCCEEDED, "ok.pdf");
        var failed = material(MaterialStatus.FAILED, "broken.pdf");
        when(fixture.materials.findAllByCourse_IdAndDeletedFalse(fixture.courseId))
                .thenReturn(List.of(success, failed));

        assertThatThrownBy(() -> fixture.service.confirm(fixture.userId, fixture.courseId, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("KNOWLEDGE_VERSION_IGNORED_MATERIALS"));
        verifyNoInteractions(fixture.versions, fixture.dispatcher);
    }

    @Test
    void createsImmutableSnapshotAndDispatchesOneOutlineTask() {
        var fixture = fixture();
        var first = material(MaterialStatus.SUCCEEDED, "a.pdf");
        var second = material(MaterialStatus.SUCCEEDED, "b.pdf");
        var failed = material(MaterialStatus.CANCELLED, "ignored.pdf");
        when(fixture.materials.findAllByCourse_IdAndDeletedFalse(fixture.courseId))
                .thenReturn(List.of(second, failed, first));
        when(fixture.versions.findByCourse_IdAndMaterialSetHash(eq(fixture.courseId), anyString()))
                .thenReturn(Optional.empty());
        when(fixture.versions.findFirstByCourse_IdOrderByVersionDesc(fixture.courseId)).thenReturn(Optional.empty());
        var version = mock(CourseKnowledgeVersion.class); var versionId = UUID.randomUUID();
        when(version.getId()).thenReturn(versionId); when(version.getVersion()).thenReturn(1L);
        when(fixture.versions.saveAndFlush(any())).thenReturn(version);
        var task = mock(BackgroundTask.class);
        when(fixture.outlineTasks.create(fixture.userId, fixture.courseId, versionId, 1))
                .thenReturn(new OutlineTaskFactory.CreatedTask(task, true));

        var result = fixture.service.confirm(fixture.userId, fixture.courseId, true);

        assertThat(result.ignoredMaterials()).containsExactly("ignored.pdf");
        verify(fixture.versionMaterials, times(2)).save(any(CourseKnowledgeVersionMaterial.class));
        verify(fixture.dispatcher).dispatch(task);
    }

    private Fixture fixture() {
        var courses = mock(CourseRepository.class); var materials = mock(MaterialRepository.class);
        var tasks = mock(BackgroundTaskRepository.class); var versions = mock(CourseKnowledgeVersionRepository.class);
        var versionMaterials = mock(CourseKnowledgeVersionMaterialRepository.class);
        var outlineTasks = mock(OutlineTaskFactory.class); var dispatcher = mock(TaskDispatchService.class);
        var uploads = mock(UploadStateStore.class); var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        var userId = UUID.randomUUID(); var courseId = UUID.randomUUID(); var course = mock(Course.class);
        when(courses.findOwnedByIdForUpdate(courseId, userId)).thenReturn(Optional.of(course));
        when(tasks.lockAllByCourseId(courseId)).thenReturn(List.of());
        var service = new KnowledgeVersionService(courses, materials, tasks, versions, versionMaterials,
                outlineTasks, dispatcher, uploads, transactions);
        return new Fixture(service, materials, tasks, versions, versionMaterials, outlineTasks, dispatcher,
                userId, courseId);
    }

    private Material material(MaterialStatus status, String filename) {
        var value = mock(Material.class); var id = UUID.randomUUID();
        when(value.getId()).thenReturn(id); when(value.getStatus()).thenReturn(status);
        when(value.getOriginalFilename()).thenReturn(filename); when(value.getContentHash()).thenReturn("a".repeat(64));
        return value;
    }

    private record Fixture(KnowledgeVersionService service, MaterialRepository materials,
                           BackgroundTaskRepository tasks, CourseKnowledgeVersionRepository versions,
                           CourseKnowledgeVersionMaterialRepository versionMaterials, OutlineTaskFactory outlineTasks,
                           TaskDispatchService dispatcher, UUID userId, UUID courseId) {}
}
