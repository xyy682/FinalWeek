package com.finalweek.outline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.ai.LlmClient;
import com.finalweek.knowledge.HybridRetrievalService;
import com.finalweek.material.MaterialRepository;
import com.finalweek.material.MaterialStatus;
import com.finalweek.task.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class OutlineGenerationPipeline implements TaskPipeline {
    private static final String RETRIEVAL_QUERY = "课程核心知识点 教师强调 考试重点 真题 题库 章节概念";
    private final HybridRetrievalService retrieval;
    private final MaterialRepository materials;
    private final OutlineCheckpointService checkpoints;
    private final OutlineGenerationValidator validator;
    private final OutlinePublisher publisher;
    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final String schema;
    public OutlineGenerationPipeline(HybridRetrievalService retrieval, MaterialRepository materials,
                                     OutlineCheckpointService checkpoints, OutlineGenerationValidator validator,
                                     OutlinePublisher publisher, LlmClient llm, ObjectMapper mapper) {
        this.retrieval = retrieval; this.materials = materials; this.checkpoints = checkpoints;
        this.validator = validator; this.publisher = publisher; this.llm = llm; this.mapper = mapper;
        try (InputStream input = getClass().getResourceAsStream("/outline-schema.json")) {
            if (input == null) throw new IllegalStateException("outline-schema.json missing");
            this.schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) { throw new IllegalStateException("无法读取提纲 JSON Schema", exception); }
    }
    @Override public TaskType type() { return TaskType.GENERATE_OUTLINE; }
    @Override public void execute(ParseTask task) {
        if (!checkpoints.completed(task.getId(), TaskStage.CONTEXT_RETRIEVED)) {
            var result = retrieval.retrieve(task.getUserId(), task.getCourseId(), RETRIEVAL_QUERY);
            if (result.hits().isEmpty()) throw new PermanentTaskException("OUTLINE_CONTEXT_EMPTY", "课程没有可生成提纲的内容");
            var ids = result.hits().stream().map(hit -> hit.segment().getId()).toList();
            checkpoints.contextRetrieved(task.getId(), new OutlineCheckpointService.OutlineContext(ids, prompt(task, result)));
        }
        var context = checkpoints.context(task.getId());
        var allowed = Set.copyOf(context.segmentIds());
        GeneratedOutline generated;
        if (!checkpoints.completed(task.getId(), TaskStage.OUTLINE_GENERATED)) {
            var raw = llm.generateJson(task.getId(), systemPrompt(), context.prompt());
            try { generated = validator.parseAndValidate(raw, allowed); }
            catch (OutlineGenerationValidator.InvalidOutlineException first) {
                var repaired = llm.generateJson(task.getId(), systemPrompt(), repairPrompt(raw, first.getMessage()));
                try { generated = validator.parseAndValidate(repaired, allowed); raw = repaired; }
                catch (OutlineGenerationValidator.InvalidOutlineException second) {
                    throw new PermanentTaskException("OUTLINE_FORMAT_INVALID", "提纲 JSON 经一次修复后仍不合法");
                }
            }
            try { checkpoints.outlineGenerated(task.getId(), mapper.writeValueAsString(generated)); }
            catch (Exception exception) { throw new PermanentTaskException("OUTLINE_FORMAT_INVALID", "提纲 JSON 无法保存"); }
        } else generated = validator.parseAndValidate(checkpoints.generatedJson(task.getId()), allowed);
        publisher.publish(task, generated, allowed);
    }

    private String systemPrompt() {
        return "你是期末复习知识提纲生成器。只能依据提供的课程片段生成，不得补充未提供的知识。"
                + "每个节点必须引用至少一个给定 segmentId。importance 只能是 HIGH、MEDIUM、LOW。"
                + "学生重点说明、教师强调内容、真题和题库信号优先提高重要度。请严格输出 JSON，不输出 Markdown。Schema:\n" + schema;
    }
    private String prompt(ParseTask task, com.finalweek.knowledge.HybridRetrievalResult result) {
        var builder = new StringBuilder("为课程生成树状复习提纲。generationVersion=")
                .append(task.getGenerationVersion()).append("\n课程资料信号:\n");
        materials.findAllByCourse_IdAndDeletedFalse(task.getCourseId()).stream()
                .filter(value -> value.getStatus() == MaterialStatus.SUCCEEDED).forEach(value -> builder
                        .append("- 类型=").append(value.getMaterialType()).append("; 文件=")
                        .append(value.getOriginalFilename()).append("; 学生重点说明=")
                        .append(value.getFocusNotes() == null ? "无" : value.getFocusNotes()).append('\n'));
        builder.append("检索片段（引用时只使用方括号内 UUID）:\n");
        result.hits().forEach(hit -> builder.append('[').append(hit.segment().getId()).append("] ")
                .append(hit.segment().getContent()).append('\n'));
        return builder.append("请按上述 JSON Schema 输出。每个节点至少一个来源。首版不生成结构编辑指令。")
                .toString();
    }
    private String repairPrompt(String raw, String reason) {
        return "下面的提纲 JSON 不符合 Schema。只修复格式和无效字段，不增加课程知识或新来源；仅输出修复后的 JSON。"
                + "\n错误：" + reason + "\n原输出：\n" + raw;
    }
}
