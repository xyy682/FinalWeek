package com.finalweek.mockexam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.ai.LlmClient;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.task.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
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

        final String raw;
        if (!checkpoints.completed(task.getId(), TaskStage.QUESTIONS_GENERATED)) {
            raw = llm.generateJson(task.getId(), systemPrompt(work.request()), userPrompt(work, context),
                    properties.requestTimeout());
            checkpoints.complete(task.getId(), TaskStage.QUESTIONS_GENERATED, raw);
        } else raw = checkpoints.resultJson(task.getId(), TaskStage.QUESTIONS_GENERATED);
        var generated = validator.parse(raw, work.request(), Set.copyOf(context.segmentIds()));

        if (!checkpoints.completed(task.getId(), TaskStage.PAPER_VALIDATED)) {
            var owned = segments.findRetrievableByIds(context.segmentIds(), task.getUserId(), task.getCourseId());
            var history = exams.findSucceededByCourseId(task.getCourseId()).stream()
                    .filter(value -> !value.getId().equals(work.exam().getId()))
                    .flatMap(value -> questions.findAllByMockExam_IdOrderByPosition(value.getId()).stream()).toList();
            var warnings = quality.check(generated, owned, history, work.request(),
                    work.exam().getHistorySimilarityThreshold(), context.nodeSegmentIds());
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
    private String systemPrompt(MockExamRequestNormalizer.Normalized request) {
        return "你是严谨的课程模拟卷生成器。只返回符合 Schema 的 JSON，不输出 Markdown。课程资料优先，禁止联网。"
                + (request.allowGeneralKnowledge() ? "仅在课程资料确实不足以满足指定题型或题量时，才可用内置通用知识补缺；该题必须 usesGeneralKnowledge=true 且来源为空。"
                : "严禁使用通用知识；每题必须完全由给定课程片段支持并引用至少一个 segmentId。若确实无法满足题型和题量，返回 status=INSUFFICIENT_MATERIAL、空 questions，并在 missingKnowledgePoints 列出具体缺口，不得返回部分试卷。")
                + "真题和例题只参考难度、结构和考法，禁止复制或轻微改写原题。普通文本与公式必须分字段。Schema:\n" + schema;
    }
    private String userPrompt(MockExamCoordinator.Work work, MockExamContextBuilder.Context context) {
        var request = work.request();
        return """
                请生成模拟卷。结构化要求（优先级最高）：
                - 题型题数：%s
                - 总题数：%d；分值模式：%s；逐题型单题分值：%s；全卷分值必须恰好为：%d
                - 范围：%s；范围节点：%s
                - 可选总分：%s；建议时长：%s
                - 用户补充说明：%s
                风格信号：检测到往年真题结构=%s；检测到教师例题/课堂练习=%s。用户要求优先于这些信号。
                选择题通常使用四个选项；多选题至少两个正确选项。参考答案应完整但精简，计算题包含必要步骤。
                课程上下文（课程题的 sourceSegmentIds 只能引用下列 ID）：
                %s
                """.formatted(request.questionCounts(), request.questionCount(), request.scoreMode(),
                request.scorePerQuestion(), request.scoreSum(), request.scope(), request.outlineNodeIds(),
                request.totalScore(), request.durationMinutes(), request.instructions(), context.pastExamSignal(),
                context.exampleSignal(), context.promptContext());
    }
}
