package com.finalweek.outline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.ai.LlmClient;
import com.finalweek.knowledge.HybridRetrievalService;
import com.finalweek.material.CourseSegment;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.material.Material;
import com.finalweek.material.MaterialRepository;
import com.finalweek.material.MaterialType;
import com.finalweek.knowledgeversion.KnowledgeVersionService;
import com.finalweek.task.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class OutlineGenerationPipeline implements TaskPipeline {
    private static final Logger log = LoggerFactory.getLogger(OutlineGenerationPipeline.class);
    private static final String SIGNAL_QUERY = "课程核心知识点 教师强调 考试重点 真题 题库 章节概念";
    private static final int MAX_CONTEXT_SEGMENTS = 80;
    private static final int MAX_SEGMENTS_PER_MATERIAL = 6;
    private final HybridRetrievalService retrieval;
    private final MaterialRepository materials;
    private final CourseSegmentRepository segments;
    private final OutlineCheckpointService checkpoints;
    private final OutlineGenerationValidator validator;
    private final OutlinePublisher publisher;
    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final String schema;
    private final KnowledgeVersionService knowledgeVersions;

    public OutlineGenerationPipeline(HybridRetrievalService retrieval, MaterialRepository materials,
                                     CourseSegmentRepository segments, OutlineCheckpointService checkpoints,
                                     OutlineGenerationValidator validator, OutlinePublisher publisher, LlmClient llm,
                                     ObjectMapper mapper, KnowledgeVersionService knowledgeVersions) {
        this.retrieval = retrieval; this.materials = materials; this.segments = segments;
        this.checkpoints = checkpoints; this.validator = validator; this.publisher = publisher;
        this.llm = llm; this.mapper = mapper; this.knowledgeVersions = knowledgeVersions;
        try (InputStream input = getClass().getResourceAsStream("/outline-schema.json")) {
            if (input == null) throw new IllegalStateException("outline-schema.json missing");
            this.schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) { throw new IllegalStateException("无法读取提纲 JSON Schema", exception); }
    }

    @Override public TaskType type() { return TaskType.GENERATE_OUTLINE; }

    @Override public void execute(BackgroundTask task) {
        boolean contextDone = checkpoints.completed(task.getId(), TaskStage.CONTEXT_RETRIEVED);
        long contextStarted = System.nanoTime();
        if (!contextDone) checkpoints.contextRetrieved(task.getId(), buildContext(task));
        log.info("Outline stage observed taskId={} stage=CONTEXT_RETRIEVED durationMs={} skippedFromCheckpoint={}",
                task.getId(), (System.nanoTime() - contextStarted) / 1_000_000, contextDone);

        var context = checkpoints.context(task.getId());
        var allowed = Set.copyOf(context.segmentIds());
        GeneratedOutline generated;
        boolean generationDone = checkpoints.completed(task.getId(), TaskStage.OUTLINE_GENERATED);
        long generationStarted = System.nanoTime();
        if (!generationDone) {
            var raw = llm.generateJson(task.getId(), systemPrompt(), context.prompt());
            try { generated = validator.parseAndValidate(raw, allowed); }
            catch (OutlineGenerationValidator.InvalidOutlineException first) {
                var repaired = llm.generateJson(task.getId(), systemPrompt(), repairPrompt(raw, first.getMessage()));
                try { generated = validator.parseAndValidate(repaired, allowed); raw = repaired; }
                catch (OutlineGenerationValidator.InvalidOutlineException second) {
                    throw new PermanentTaskException("OUTLINE_FORMAT_INVALID",
                            "提纲内容或结构经一次修复后仍不合法：" + second.getMessage());
                }
            }
            try { checkpoints.outlineGenerated(task.getId(), mapper.writeValueAsString(generated)); }
            catch (Exception exception) { throw new PermanentTaskException("OUTLINE_FORMAT_INVALID", "提纲 JSON 无法保存"); }
        } else generated = validator.parseAndValidate(checkpoints.generatedJson(task.getId()), allowed);
        log.info("Outline stage observed taskId={} stage=OUTLINE_GENERATED durationMs={} skippedFromCheckpoint={}",
                task.getId(), (System.nanoTime() - generationStarted) / 1_000_000, generationDone);
        publisher.publish(task, generated, allowed);
    }

    private OutlineCheckpointService.OutlineContext buildContext(BackgroundTask task) {
        var versionMaterialIds = List.copyOf(knowledgeVersions.materialIds(task.getBusinessId()));
        var allowedMaterialIds = new LinkedHashSet<>(versionMaterialIds);
        var materialById = new LinkedHashMap<UUID, Material>();
        materials.findAllByCourse_IdAndDeletedFalse(task.getCourseId()).stream()
                .filter(value -> allowedMaterialIds.contains(value.getId()))
                .forEach(value -> materialById.put(value.getId(), value));

        var selected = new LinkedHashMap<UUID, CourseSegment>();
        int perMaterial = Math.min(MAX_SEGMENTS_PER_MATERIAL,
                Math.max(1, MAX_CONTEXT_SEGMENTS / Math.max(1, versionMaterialIds.size())));
        for (var materialId : versionMaterialIds) {
            sampleEvenly(segments.findAllByMaterial_IdOrderByChunkNo(materialId), perMaterial)
                    .forEach(value -> selected.putIfAbsent(value.getId(), value));
        }

        var signalResult = retrieval.retrieve(task.getUserId(), task.getCourseId(), SIGNAL_QUERY, allowedMaterialIds);
        signalResult.hits().forEach(hit -> {
            if (selected.size() < MAX_CONTEXT_SEGMENTS) selected.putIfAbsent(hit.segment().getId(), hit.segment());
        });
        if (selected.isEmpty()) throw new PermanentTaskException("OUTLINE_CONTEXT_EMPTY", "课程没有可生成提纲的内容");

        var prompt = prompt(task, materialById, selected.values());
        return new OutlineCheckpointService.OutlineContext(List.copyOf(selected.keySet()), prompt);
    }

    private List<CourseSegment> sampleEvenly(List<CourseSegment> values, int limit) {
        if (values.size() <= limit) return values;
        var result = new ArrayList<CourseSegment>();
        if (limit == 1) return List.of(values.get(values.size() / 2));
        for (int index = 0; index < limit; index++) {
            int position = (int) Math.round(index * (values.size() - 1d) / (limit - 1d));
            result.add(values.get(position));
        }
        return result;
    }

    private String systemPrompt() {
        return "你是课程知识结构设计器，只能依据提供的课程片段生成知识提纲，不得补充未提供的知识。"
                + "节点标题只能是概念、原理、模型、算法、方法或可学习技能；严禁把考试重点、考试形式、题型、分值、"
                + "复习建议、课程通知、真题、题库或资料目录作为知识节点。考试与教师强调信号只能影响 importance。"
                + "优先保证各资料和章节的知识覆盖，再根据考试信号标注重要度。根节点也必须是知识领域，不得使用‘核心复习提纲’等空标题。"
                + "每个节点必须引用至少一个给定 segmentId。importance 只能是 HIGH、MEDIUM、LOW。"
                + "请严格输出 JSON，不输出 Markdown。Schema:\n" + schema;
    }
    private String prompt(BackgroundTask task, Map<UUID, Material> materialById,
                          Collection<CourseSegment> selected) {
        var builder = new StringBuilder("为课程生成树状知识提纲。generationVersion=")
                .append(task.getGenerationVersion()).append("\n资料清单与信号（考试信号只影响重要度，不得成为节点）：\n");
        materialById.values().forEach(value -> builder.append("- 类型=").append(value.getMaterialType())
                .append("; 文件=").append(value.getOriginalFilename()).append("; 学生重点说明=")
                .append(value.getFocusNotes() == null ? "无" : value.getFocusNotes()).append('\n'));
        builder.append("覆盖优先的课程片段（引用只使用方括号内 UUID）：\n");
        for (var segment : selected) {
            var material = materialById.get(segment.getMaterialId());
            var type = material == null ? MaterialType.OTHER : material.getMaterialType();
            builder.append('[').append(segment.getId()).append("] 文件=")
                    .append(material == null ? "课程资料" : material.getOriginalFilename())
                    .append("; 类型=").append(type).append("; 位置=").append(location(segment))
                    .append("; 信号用途=").append(type == MaterialType.PAST_EXAM || type == MaterialType.QUESTION_BANK
                            ? "只用于考法与重要度，标题仍必须是知识点" : "知识内容")
                    .append('\n').append(segment.getContent()).append("\n---\n");
        }
        return builder.append("输出要求：先按学科知识域组织，再细分概念与方法；同一来源不得支配大多数节点；"
                        + "尽量覆盖不同资料和章节。每个节点至少一个来源。首版不生成结构编辑指令。")
                .toString();
    }
    private String location(CourseSegment value) {
        if (value.getPageNumber() != null) return "第" + value.getPageNumber() + "页";
        if (value.getSlideNumber() != null) return "第" + value.getSlideNumber() + "张";
        if (value.getParagraphNumber() != null) return "第" + value.getParagraphNumber() + "段";
        if (value.getStartTimeMs() != null) return "时间" + value.getStartTimeMs() + "ms";
        return "原文位置";
    }

    private String repairPrompt(String raw, String reason) {
        return "下面的提纲 JSON 不符合知识提纲规则。删除考试形式、题型、分值、通知、资料目录等非知识节点，"
                + "修正来源集中或格式问题；不得增加课程知识或新来源，仅输出修复后的 JSON。"
                + "\n错误：" + reason + "\n原输出：\n" + raw;
    }
}