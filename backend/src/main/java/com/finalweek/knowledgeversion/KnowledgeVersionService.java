package com.finalweek.knowledgeversion;

import com.finalweek.common.api.BusinessException;
import com.finalweek.course.Course;
import com.finalweek.course.CourseRepository;
import com.finalweek.material.Material;
import com.finalweek.material.MaterialRepository;
import com.finalweek.material.MaterialStatus;
import com.finalweek.outline.OutlineTaskFactory;
import com.finalweek.task.*;
import com.finalweek.upload.UploadStateStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KnowledgeVersionService {
    private static final List<TaskStatus> MATERIAL_BUSY = List.of(TaskStatus.PENDING_PUBLISH, TaskStatus.QUEUED,
            TaskStatus.PROCESSING, TaskStatus.RETRYING);
    private static final List<TaskStatus> OUTLINE_ACTIVE = List.of(TaskStatus.PENDING_PUBLISH,
            TaskStatus.PUBLISH_FAILED, TaskStatus.QUEUED, TaskStatus.PROCESSING, TaskStatus.RETRYING);
    private final CourseRepository courses;
    private final MaterialRepository materials;
    private final BackgroundTaskRepository tasks;
    private final CourseKnowledgeVersionRepository versions;
    private final CourseKnowledgeVersionMaterialRepository versionMaterials;
    private final OutlineTaskFactory outlineTasks;
    private final TaskDispatchService dispatcher;
    private final UploadStateStore uploads;
    private final TransactionTemplate transactions;

    public KnowledgeVersionService(CourseRepository courses, MaterialRepository materials,
                                   BackgroundTaskRepository tasks, CourseKnowledgeVersionRepository versions,
                                   CourseKnowledgeVersionMaterialRepository versionMaterials,
                                   OutlineTaskFactory outlineTasks, TaskDispatchService dispatcher,
                                   UploadStateStore uploads, PlatformTransactionManager transactionManager) {
        this.courses = courses; this.materials = materials; this.tasks = tasks; this.versions = versions;
        this.versionMaterials = versionMaterials; this.outlineTasks = outlineTasks; this.dispatcher = dispatcher;
        this.uploads = uploads; this.transactions = new TransactionTemplate(transactionManager);
    }

    public Confirmation confirm(UUID userId, UUID courseId, boolean ignoreFailedMaterials) {
        var created = transactions.execute(status -> create(userId, courseId, ignoreFailedMaterials));
        if (created == null) throw new IllegalStateException("知识版本事务未返回结果");
        dispatcher.dispatch(created.task());
        var refreshed = tasks.findById(created.task().getId()).orElse(created.task());
        return new Confirmation(created.version(), refreshed, created.ignoredMaterials());
    }

    private Confirmation create(UUID userId, UUID courseId, boolean ignoreFailedMaterials) {
        Course course = courses.findOwnedByIdForUpdate(courseId, userId).orElseThrow(this::notFound);
        if (uploads.activeCount(courseId) > 0) throw conflict("KNOWLEDGE_VERSION_MATERIALS_BUSY", "仍有资料正在上传");
        var lockedTasks = tasks.lockAllByCourseId(courseId);
        long busy = lockedTasks.stream().filter(task -> task.getTaskType() == TaskType.PARSE_MATERIAL)
                .filter(task -> MATERIAL_BUSY.contains(task.getStatus())).count();
        if (busy > 0) throw conflict("KNOWLEDGE_VERSION_MATERIALS_BUSY", "仍有 " + busy + " 份资料尚未处理完成");

        var all = materials.findAllByCourse_IdAndDeletedFalse(courseId);
        var successful = all.stream().filter(value -> value.getStatus() == MaterialStatus.SUCCEEDED)
                .sorted(Comparator.comparing(value -> value.getId().toString())).toList();
        if (successful.isEmpty()) throw conflict("KNOWLEDGE_VERSION_EMPTY", "至少需要一份解析成功的资料");
        var ignored = all.stream().filter(value -> value.getStatus() == MaterialStatus.FAILED
                || value.getStatus() == MaterialStatus.CANCELLED || value.getStatus() == MaterialStatus.PUBLISH_FAILED)
                .map(Material::getOriginalFilename).sorted().toList();
        if (!ignored.isEmpty() && !ignoreFailedMaterials) throw conflict("KNOWLEDGE_VERSION_IGNORED_MATERIALS",
                "以下资料不会进入本次版本，请确认忽略后继续：" + String.join("、", ignored));

        var hash = materialSetHash(successful);
        if (versions.findByCourse_IdAndMaterialSetHash(courseId, hash).isPresent())
            throw conflict("KNOWLEDGE_VERSION_UNCHANGED", "成功资料集合没有变化，无需重复确认");
        if (tasks.findFirstByCourseIdAndTaskTypeAndStatusInOrderByCreatedAtDesc(
                courseId, TaskType.GENERATE_OUTLINE, OUTLINE_ACTIVE).isPresent())
            throw conflict("KNOWLEDGE_VERSION_MATERIALS_BUSY", "本课程已有提纲生成任务");

        long nextVersion = versions.findFirstByCourse_IdOrderByVersionDesc(courseId)
                .map(value -> value.getVersion() + 1).orElse(1L);
        var version = versions.saveAndFlush(new CourseKnowledgeVersion(course, nextVersion, hash));
        for (int index = 0; index < successful.size(); index++) versionMaterials.save(
                new CourseKnowledgeVersionMaterial(version.getId(), successful.get(index).getId(), index));
        versionMaterials.flush();
        var task = outlineTasks.create(userId, courseId, version.getId(), nextVersion).task();
        return new Confirmation(version, task, ignored);
    }

    @Transactional(readOnly = true)
    public StatusView status(UUID userId, UUID courseId) {
        var course = courses.findByIdAndUserIdAndDeletedFalse(courseId, userId).orElseThrow(this::notFound);
        var current = course.getCurrentKnowledgeVersionId() == null ? null
                : versions.findById(course.getCurrentKnowledgeVersionId()).orElse(null);
        var reference = versions.findFirstByCourse_IdOrderByVersionDesc(courseId).orElse(current);
        var included = reference == null ? Set.<UUID>of() : versionMaterials
                .findAllByKnowledgeVersionIdOrderByPosition(reference.getId()).stream()
                .map(CourseKnowledgeVersionMaterial::getMaterialId).collect(java.util.stream.Collectors.toSet());
        boolean unconfirmed = materials.findAllByCourse_IdAndDeletedFalse(courseId).stream()
                .anyMatch(value -> value.getStatus() == MaterialStatus.SUCCEEDED && !included.contains(value.getId()));
        var active = tasks.findFirstByCourseIdAndTaskTypeAndStatusInOrderByCreatedAtDesc(
                courseId, TaskType.GENERATE_OUTLINE, OUTLINE_ACTIVE).orElse(null);
        var failed = tasks.findFirstByCourseIdAndTaskTypeAndStatusInOrderByCreatedAtDesc(
                courseId, TaskType.GENERATE_OUTLINE, List.of(TaskStatus.FAILED)).orElse(null);
        if (failed != null) {
            var failedVersion = versions.findById(failed.getBusinessId()).orElse(null);
            if (failedVersion == null || failedVersion.getStatus() != KnowledgeVersionStatus.FAILED
                    || current != null && failedVersion.getVersion() <= current.getVersion()) failed = null;
        }
        return new StatusView(current == null ? null : VersionView.from(current), unconfirmed,
                active == null ? null : TaskProgressService.TaskView.from(active),
                failed == null ? null : TaskProgressService.TaskView.from(failed), current != null);
    }

    public List<UUID> materialIds(UUID knowledgeVersionId) {
        return versionMaterials.findAllByKnowledgeVersionIdOrderByPosition(knowledgeVersionId).stream()
                .map(CourseKnowledgeVersionMaterial::getMaterialId).toList();
    }

    private String materialSetHash(List<Material> values) {
        var canonical = values.stream().map(value -> value.getId().toString().replace("-", "").toUpperCase(Locale.ROOT)
                        + ":" + Objects.toString(value.getContentHash(), ""))
                .sorted().collect(java.util.stream.Collectors.joining("|"));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("无法计算资料集合哈希", exception); }
    }
    private BusinessException notFound() { return new BusinessException(HttpStatus.NOT_FOUND,
            "COURSE_NOT_FOUND", "课程不存在或无权访问"); }
    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    public record Confirmation(CourseKnowledgeVersion version, BackgroundTask task, List<String> ignoredMaterials) {}
    public record VersionView(UUID id, long version, KnowledgeVersionStatus status, UUID outlineId,
                              java.time.Instant createdAt, java.time.Instant publishedAt, String errorCode) {
        static VersionView from(CourseKnowledgeVersion value) { return new VersionView(value.getId(), value.getVersion(),
                value.getStatus(), value.getOutlineId(), value.getCreatedAt(), value.getPublishedAt(), value.getErrorCode()); }
    }
    public record StatusView(VersionView current, boolean hasUnconfirmedSuccessfulMaterials,
                             TaskProgressService.TaskView activeOutlineTask,
                             TaskProgressService.TaskView recentFailedOutlineTask,
                             boolean planAndMockExamAvailable) {}
}
