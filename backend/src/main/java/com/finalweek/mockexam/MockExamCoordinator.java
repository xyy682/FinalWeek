package com.finalweek.mockexam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.api.BusinessException;
import com.finalweek.course.CourseRepository;
import com.finalweek.knowledgeversion.CourseKnowledgeVersionRepository;
import com.finalweek.outline.*;
import com.finalweek.task.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MockExamCoordinator {
    private static final List<TaskStatus> ACTIVE = List.of(TaskStatus.PENDING_PUBLISH, TaskStatus.PUBLISH_FAILED,
            TaskStatus.QUEUED, TaskStatus.PROCESSING, TaskStatus.RETRYING);
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Shanghai");
    private final CourseRepository courses;
    private final CourseKnowledgeVersionRepository versions;
    private final OutlineRepository outlines;
    private final OutlineNodeRepository nodes;
    private final MockExamRepository exams;
    private final BackgroundTaskRepository tasks;
    private final MockExamRequestNormalizer normalizer;
    private final ObjectMapper mapper;
    private final MockExamProperties properties;
    private final MockExamPdfProperties pdfProperties;

    public MockExamCoordinator(CourseRepository courses, CourseKnowledgeVersionRepository versions,
                               OutlineRepository outlines, OutlineNodeRepository nodes,
                               MockExamRepository exams, BackgroundTaskRepository tasks,
                               MockExamRequestNormalizer normalizer, ObjectMapper mapper, MockExamProperties properties,
                               MockExamPdfProperties pdfProperties) {
        this.courses = courses; this.versions = versions; this.outlines = outlines; this.nodes = nodes;
        this.exams = exams; this.tasks = tasks; this.normalizer = normalizer; this.mapper = mapper;
        this.properties = properties;
        this.pdfProperties = pdfProperties;
    }

    @Transactional
    public Created create(UUID userId, UUID courseId, String key, MockExamRequestNormalizer.Request input,
                          UUID retryOfId) {
        validateKey(key);
        var course = courses.findOwnedByIdForUpdate(courseId, userId).orElseThrow(this::notFound);
        var allTasks = tasks.lockAllByCourseId(courseId);
        var active = allTasks.stream().filter(value -> value.getTaskType() == TaskType.GENERATE_MOCK_EXAM)
                .filter(value -> ACTIVE.contains(value.getStatus())).findFirst();
        var outline = course.getCurrentKnowledgeVersionId() == null ? null
                : outlines.findByKnowledgeVersion_Id(course.getCurrentKnowledgeVersionId()).orElse(null);
        if (outline == null) throw new BusinessException(HttpStatus.CONFLICT, "MOCK_EXAM_REQUIRES_OUTLINE",
                "请先确认资料并等待课程知识提纲发布");
        var outlineNodes = nodes.findAllByOutline_IdOrderByPosition(outline.getId());
        var expanded = expand(input, outlineNodes);
        var normalized = normalizer.normalize(input, expanded);
        final String json;
        try { json = mapper.writeValueAsString(normalized); }
        catch (Exception exception) { throw new IllegalStateException("无法保存模拟卷参数", exception); }
        var hash = sha256(json);
        var replay = exams.findByUserIdAndCourseIdAndIdempotencyKey(userId, courseId, key).orElse(null);
        if (replay != null) {
            if (!replay.getRequestHash().equals(hash)) throw new BusinessException(HttpStatus.CONFLICT,
                    "MOCK_EXAM_IDEMPOTENCY_MISMATCH", "同一幂等键不能用于不同模拟卷参数");
            return new Created(replay, tasks.findById(replay.getTaskId()).orElseThrow(), true);
        }
        if (active.isPresent()) throw new BusinessException(HttpStatus.CONFLICT,
                "MOCK_EXAM_GENERATION_IN_PROGRESS", "本课程已有模拟卷生成任务");
        var retryOf = retryOfId == null ? null : exams.findOwned(retryOfId, userId)
                .filter(value -> value.getCourseId().equals(courseId)).orElseThrow(this::notFound);
        var version = versions.findById(course.getCurrentKnowledgeVersionId()).orElseThrow();
        var display = normalizeName(input.displayName(), course.getName());
        var exam = exams.saveAndFlush(new MockExam(userId, courseId, version, retryOf, key, display,
                course.getName() + "模拟卷", json, hash, normalized, properties.qualityPolicyVersion(),
                properties.historySimilarityWarningThreshold(), pdfProperties.templateVersion(),
                pdfProperties.formulaPolicyVersion()));
        var task = tasks.saveAndFlush(new BackgroundTask(userId, courseId, TaskType.GENERATE_MOCK_EXAM,
                exam.getId(), true));
        exam.attachTask(task); exams.saveAndFlush(exam);
        return new Created(exam, task, false);
    }

    @Transactional(readOnly = true)
    public Work work(UUID examId) {
        var exam = exams.findById(examId).orElseThrow();
        final MockExamRequestNormalizer.Normalized request;
        try { request = mapper.readValue(exam.getRequestJson(), MockExamRequestNormalizer.Normalized.class); }
        catch (Exception exception) { throw new PermanentTaskException("MOCK_EXAM_REQUEST_INVALID", "模拟卷参数无法恢复"); }
        var outline = outlines.findByKnowledgeVersion_Id(exam.getKnowledgeVersionId())
                .orElseThrow(() -> new PermanentTaskException("MOCK_EXAM_REQUIRES_OUTLINE", "知识版本提纲不存在"));
        var allNodes = nodes.findAllByOutline_IdOrderByPosition(outline.getId());
        var selected = request.scope() == MockExamScope.WHOLE_COURSE ? allNodes : allNodes.stream()
                .filter(value -> request.outlineNodeIds().contains(value.getId())).toList();
        return new Work(exam, request, selected);
    }

    @Transactional(readOnly = true)
    public MockExam owned(UUID userId, UUID examId) { return exams.findOwned(examId, userId).orElseThrow(this::notFound); }

    @Transactional(readOnly = true)
    public PageView page(UUID userId, UUID courseId, int page, int size) {
        courses.findByIdAndUserIdAndDeletedFalse(courseId, userId).orElseThrow(this::notFound);
        int safeSize = Math.min(50, Math.max(1, size));
        var values = exams.pageOwned(userId, courseId, PageRequest.of(Math.max(0, page), safeSize));
        return new PageView(values.getContent().stream().map(value -> Summary.from(value,
                        tasks.findById(value.getTaskId()).orElse(null))).toList(), values.getNumber(),
                values.getSize(), values.getTotalElements(), values.getTotalPages());
    }

    @Transactional
    public void fail(UUID examId, String code, String message) { exams.findById(examId).ifPresent(value -> {
        value.fail(code, message == null ? null : message.substring(0, Math.min(1000, message.length()))); exams.save(value); }); }
    @Transactional
    public void state(UUID examId, TaskStatus status) { exams.findById(examId).ifPresent(value -> { value.status(status); exams.save(value); }); }
    @Transactional
    public void retryState(UUID examId) { exams.findById(examId).ifPresent(value -> { value.retrying(); exams.save(value); }); }
    @Transactional
    public void warnings(UUID examId, List<String> values) {
        exams.findById(examId).ifPresent(exam -> { try { exam.warnings(mapper.writeValueAsString(values)); exams.save(exam); }
        catch (Exception exception) { throw new IllegalStateException(exception); } });
    }
    @Transactional
    public void artifacts(UUID examId, String paper, String answer) {
        var exam = exams.findById(examId).orElseThrow(); exam.artifacts(paper, answer); exams.save(exam);
    }
    @Transactional
    public void delete(UUID userId, UUID examId) {
        var exam = owned(userId, examId);
        if (!exam.getStatus().terminal()) throw new BusinessException(HttpStatus.CONFLICT,
                "MOCK_EXAM_NOT_DELETABLE", "仅终态模拟卷可以删除");
        exam.delete(); exams.save(exam);
    }

    private Set<UUID> expand(MockExamRequestNormalizer.Request input, List<OutlineNode> values) {
        if (input.scope() != MockExamScope.OUTLINE_NODES) return Set.of();
        var requested = input.outlineNodeIds() == null ? Set.<UUID>of() : Set.copyOf(input.outlineNodeIds());
        var byParent = new HashMap<UUID, List<UUID>>(); var known = new HashSet<UUID>();
        values.forEach(value -> { known.add(value.getId()); if (value.getParentId() != null)
            byParent.computeIfAbsent(value.getParentId(), ignored -> new ArrayList<>()).add(value.getId()); });
        if (!known.containsAll(requested)) throw new BusinessException(HttpStatus.BAD_REQUEST,
                "MOCK_EXAM_REQUEST_INVALID", "范围包含不属于当前提纲的节点");
        var result = new LinkedHashSet<UUID>(); var stack = new ArrayDeque<>(requested);
        while (!stack.isEmpty()) { var id = stack.pop(); if (result.add(id)) stack.addAll(byParent.getOrDefault(id, List.of())); }
        return result;
    }
    private String normalizeName(String value, String courseName) {
        if (value != null && !value.isBlank()) {
            var stripped = value.strip(); if (stripped.length() > 120) throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "MOCK_EXAM_REQUEST_INVALID", "模拟卷名称不能超过 120 字"); return stripped;
        }
        return courseName + "模拟卷 " + java.time.ZonedDateTime.now(USER_ZONE).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }
    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,80}")) throw new BusinessException(HttpStatus.BAD_REQUEST,
                "MOCK_EXAM_IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 必须为 8–80 位安全字符");
    }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private BusinessException notFound() { return new BusinessException(HttpStatus.NOT_FOUND,
            "MOCK_EXAM_NOT_FOUND", "模拟卷不存在或无权访问"); }

    public record Created(MockExam exam, BackgroundTask task, boolean replay) {}
    public record Work(MockExam exam, MockExamRequestNormalizer.Normalized request, List<OutlineNode> nodes) {}
    public record Summary(UUID id, UUID taskId, UUID retryOfId, String displayName, TaskStatus status,
                          TaskStage currentStage, int questionCount,
                          int scoreSum, String errorCode, String errorMessage, List<String> warnings, java.time.Instant createdAt,
                          java.time.Instant completedAt) {
        static Summary from(MockExam exam, BackgroundTask task) { return new Summary(exam.getId(), exam.getTaskId(),
                exam.getRetryOfId(), exam.getDisplayName(), exam.getStatus(),
                task == null ? null : task.getCurrentStage(), exam.getQuestionCount(), exam.getScoreSum(),
                exam.getErrorCode(), exam.getErrorMessage(),
                parseWarnings(exam.getWarningsJson()), exam.getCreatedAt(), exam.getCompletedAt()); }
        private static List<String> parseWarnings(String value) { try { return new ObjectMapper().readValue(value,
                new TypeReference<List<String>>() {}); } catch (Exception ignored) { return List.of(); } }
    }
    public record PageView(List<Summary> items, int page, int size, long totalElements, int totalPages) {}
}
