package com.finalweek.mockexam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.ai.LlmClient;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.task.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

@Service
public class MockExamGenerationPipeline implements TaskPipeline {
    private final MockExamCoordinator coordinator;
    private final MockExamContextBuilder contexts;
    private final MockExamValidator validator;
    private final MockExamQualityChecker quality;
    private final MockExamPublisher publisher;
    private final MockExamRepository exams;
    private final MockExamQuestionRepository questions;
    private final CourseSegmentRepository segments;
    private final TaskCheckpointService checkpoints;
    private final MockExamArtifactGenerator artifacts;
    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final MockExamProperties properties;
    private final String schema;

    public MockExamGenerationPipeline(MockExamCoordinator coordinator, MockExamContextBuilder contexts,
                                      MockExamValidator validator, MockExamQualityChecker quality,
                                      MockExamPublisher publisher, MockExamRepository exams,
                                      MockExamQuestionRepository questions, CourseSegmentRepository segments,
                                      TaskCheckpointService checkpoints, MockExamArtifactGenerator artifacts,
                                      LlmClient llm, ObjectMapper mapper, MockExamProperties properties) {
        this.coordinator = coordinator; this.contexts = contexts; this.validator = validator; this.quality = quality;
        this.publisher = publisher; this.exams = exams; this.questions = questions; this.segments = segments;
        this.checkpoints = checkpoints; this.artifacts = artifacts; this.llm = llm; this.mapper = mapper;
        this.properties = properties;
        try (InputStream input = getClass().getResourceAsStream("/mock-exam-schema.json")) {
            if (input == null) throw new IllegalStateException("mock-exam-schema.json missing");
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) { throw new IllegalStateException("无法读取模拟卷 JSON Schema", exception); }
    }
    @Override public TaskType type() { return TaskType.GENERATE_MOCK_EXAM; }
    @Override public void execute(BackgroundTask task) {
        var work = coordinator.work(task.getBusinessId());
        if (!Objects.equals(work.exam().getQualityPolicyVersion(), properties.qualityPolicyVersion()))
            throw new PermanentTaskException("MOCK_EXAM_POLICY_VERSION_UNAVAILABLE",
                    "任务创建时的模拟卷质量策略版本已不可用，请从失败记录创建新的重试");
        final MockExamContextBuilder.Context context;
        try {
            if (!checkpoints.completed(task.getId(), TaskStage.REQUIREMENTS_ANALYZED)) {
                context = contexts.build(work);
                checkpoints.complete(task.getId(), TaskStage.REQUIREMENTS_ANALYZED, mapper.writeValueAsString(context));
            } else context = mapper.readValue(checkpoints.resultJson(task.getId(), TaskStage.REQUIREMENTS_ANALYZED),
                    MockExamContextBuilder.Context.class);
        } catch (PermanentTaskException exception) { throw exception; }
        catch (Exception exception) { throw new PermanentTaskException("MOCK_EXAM_REQUEST_INVALID", "出卷上下文无法保存或恢复"); }

        var owned = segments.findRetrievableByIds(context.segmentIds(), task.getUserId(), task.getCourseId());
        var history = exams.findSucceededByCourseId(task.getCourseId()).stream()
                .filter(value -> !value.getId().equals(work.exam().getId()))
                .flatMap(value -> questions.findAllByMockExam_IdOrderByPosition(value.getId()).stream()).toList();
        boolean restored = checkpoints.completed(task.getId(), TaskStage.QUESTIONS_GENERATED);
        final GeneratedMockExam generated;
        final String raw;
        if (restored) {
            raw = checkpoints.resultJson(task.getId(), TaskStage.QUESTIONS_GENERATED);
            generated = validator.parse(raw, work.request(), Set.copyOf(context.segmentIds()));
        } else {
            generated = generateByType(task, work, context, owned, history);
            try { raw = mapper.writeValueAsString(generated); }
            catch (Exception exception) { throw new PermanentTaskException("MOCK_EXAM_FORMAT_INVALID", "试卷生成结果无法保存"); }
            checkpoints.complete(task.getId(), TaskStage.QUESTIONS_GENERATED, raw);
        }
        var warnings = quality.check(generated, owned, history, work.request(),
                work.exam().getHistorySimilarityThreshold(), context.nodeSegmentIds());
        if (!checkpoints.completed(task.getId(), TaskStage.PAPER_VALIDATED)) {
            publisher.publishQuestions(work.exam().getId(), generated, warnings);
            try { checkpoints.complete(task.getId(), TaskStage.PAPER_VALIDATED, mapper.writeValueAsString(warnings)); }
            catch (Exception exception) { throw new PermanentTaskException("MOCK_EXAM_FORMAT_INVALID", "试卷校验结果无法保存"); }
        }
        if (!checkpoints.completed(task.getId(), TaskStage.PDFS_GENERATED)) {
            var result = artifacts.generate(task.getId(), work.exam(), generated);
            coordinator.artifacts(work.exam().getId(), result.paperObjectKey(), result.answerObjectKey());
            checkpoints.complete(task.getId(), TaskStage.PDFS_GENERATED, "{}");
        }
    }
    private GeneratedMockExam generateByType(BackgroundTask task, MockExamCoordinator.Work work,
                                               MockExamContextBuilder.Context context,
                                               List<com.finalweek.material.CourseSegment> owned,
                                               List<MockExamQuestion> history) {
        var entries = new ArrayList<>(work.request().questionCounts().entrySet());
        var pool = Executors.newFixedThreadPool(Math.min(2, entries.size()));
        try {
            var futures = entries.stream().map(entry -> pool.submit(() -> generateTypeBatch(task, work, context,
                    entry.getKey(), entry.getValue(), owned, history))).toList();
            var all = new ArrayList<GeneratedMockExam.Question>();
            for (var future : futures) all.addAll(future.get());
            var combined = new GeneratedMockExam(List.copyOf(all));
            return validator.parse(mapper.writeValueAsString(combined), work.request(), Set.copyOf(context.segmentIds()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PermanentTaskException("MOCK_EXAM_GENERATION_INTERRUPTED", "模拟卷生成被中断");
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new PermanentTaskException("MOCK_EXAM_GENERATION_FAILED", "模拟卷分题型生成失败");
        } catch (PermanentTaskException exception) { throw exception; }
        catch (Exception exception) { throw new PermanentTaskException("MOCK_EXAM_FORMAT_INVALID", "分题型试卷无法合并"); }
        finally { pool.shutdownNow(); }
    }

    private List<GeneratedMockExam.Question> generateTypeBatch(BackgroundTask task, MockExamCoordinator.Work work,
                                                                 MockExamContextBuilder.Context context,
                                                                 MockExamQuestionType type, int count,
                                                                 List<com.finalweek.material.CourseSegment> owned,
                                                                 List<MockExamQuestion> history) {
        var request = batchRequest(work.request(), type, count);
        String previous = null;
        String reason = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            var prompt = typePrompt(request, context, type, count);
            if (previous != null) prompt += "\n" + repairPrompt(previous, reason);
            var raw = llm.generateJson(task.getId(), systemPrompt(request), prompt, properties.requestTimeout());
            try {
                var generated = validator.parse(raw, request, Set.copyOf(context.segmentIds()));
                quality.check(generated, owned, history, request,
                        work.exam().getHistorySimilarityThreshold(), context.nodeSegmentIds());
                return generated.questions();
            } catch (PermanentTaskException exception) {
                if (attempt == 1 || !Set.of("MOCK_EXAM_FORMAT_INVALID", "MOCK_EXAM_SOURCE_INVALID",
                        "MOCK_EXAM_SOURCE_REPLICATED").contains(exception.code())) throw exception;
                previous = raw;
                reason = exception.getMessage();
            }
        }
        throw new PermanentTaskException("MOCK_EXAM_GENERATION_FAILED", "题型批次生成失败");
    }

    private MockExamRequestNormalizer.Normalized batchRequest(MockExamRequestNormalizer.Normalized original,
                                                                MockExamQuestionType type, int count) {
        return new MockExamRequestNormalizer.Normalized(original.scope(), original.outlineNodeIds(), Map.of(type, count),
                ScoreMode.AUTO, Map.of(), null, original.durationMinutes(), original.allowGeneralKnowledge(),
                original.instructions(), count, count);
    }
    private String systemPrompt(MockExamRequestNormalizer.Normalized request) {
        return "你是严谨的课程模拟卷生成器。只返回符合 Schema 的 JSON，不输出 Markdown。课程资料优先，禁止联网。"
                + (request.allowGeneralKnowledge() ? "仅在课程资料确实不足以满足指定题型或题量时，才可用内置通用知识补缺；该题必须 usesGeneralKnowledge=true 且来源为空。"
                : "严禁使用通用知识；每题必须完全由给定课程片段支持并引用至少一个 segmentId。若确实无法满足题型和题量，返回 status=INSUFFICIENT_MATERIAL、空 questions，并在 missingKnowledgePoints 列出具体缺口，不得返回部分试卷。")
                + "单选题和多选题都必须且只能有四个选项；其他题型 options 必须是空数组。综合题应包含一段案例或材料以及多个相互关联的小问，并在 answer.referenceAnswer 中给出分点答案。真题和例题只参考难度、结构和考法，禁止复制或轻微改写原题。sourceSegmentIds 只能逐字复制上下文中提供的 UUID，严禁编造；普通文本与公式必须分字段。Schema:\n" + schema;
    }
    private String repairPrompt(String raw, String reason) {
        return "上一次试卷未通过服务端校验。请保留合格题目，仅重写不合格题目，并重新返回完整 JSON。"
                + "分值由服务端统一分配，不要通过改变题数解决；来源 UUID 只能从原课程上下文逐字复制；禁止复制或轻微改写资料原题。"
                + "\n校验失败原因：" + reason + "\n上一次输出：\n" + raw;
    }
    private String typePrompt(MockExamRequestNormalizer.Normalized request, MockExamContextBuilder.Context context,
                              MockExamQuestionType type, int count) {
        return """
                本次只生成一个题型批次，不得混入其他题型：
                - questionType 必须全部为 %s，questions 必须恰好 %d 题
                - 每题 score 暂填 1，最终分值由服务端统一分配
                - 单选题和多选题必须且只能提供 4 个非空选项；非选择题 options 必须为 []
                - 综合题应提供案例/材料和多个关联小问，answer.referenceAnswer 给出对应的分点答案
                - 范围：%s；范围节点：%s；用户补充说明：%s
                风格信号：检测到往年真题结构=%s；检测到教师例题/课堂练习=%s。只参考考法，禁止复制或轻微改写原题。
                课程上下文（课程题的 sourceSegmentIds 只能引用下列 ID）：
                %s
                """.formatted(type, count, request.scope(), request.outlineNodeIds(), request.instructions(),
                context.pastExamSignal(), context.exampleSignal(), context.promptContext());
    }
}
