package com.finalweek.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.ai.LlmClient;
import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.knowledge.HybridRetrievalService;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.task.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class ChatAnswerPipeline implements TaskPipeline {
    private final ChatMessageRepository messages;
    private final ChatCoordinator coordinator;
    private final HybridRetrievalService retrieval;
    private final CourseSegmentRepository segments;
    private final ChatAnswerValidator validator;
    private final TaskCheckpointService checkpoints;
    private final LlmClient llm;
    private final FinalWeekProperties properties;
    private final ObjectMapper mapper;

    public ChatAnswerPipeline(ChatMessageRepository messages, ChatCoordinator coordinator,
                              HybridRetrievalService retrieval, CourseSegmentRepository segments,
                              ChatAnswerValidator validator, TaskCheckpointService checkpoints,
                              LlmClient llm, FinalWeekProperties properties, ObjectMapper mapper) {
        this.messages = messages; this.coordinator = coordinator; this.retrieval = retrieval; this.segments = segments;
        this.validator = validator; this.checkpoints = checkpoints; this.llm = llm;
        this.properties = properties; this.mapper = mapper;
    }

    @Override public TaskType type() { return TaskType.ANSWER_CHAT; }

    @Override public void execute(BackgroundTask task) {
        var question = messages.findById(task.getBusinessId()).orElseThrow(() ->
                new PermanentTaskException("CHAT_MESSAGE_NOT_FOUND", "聊天问题不存在"));
        if (question.getStatus() != ChatMessageStatus.PENDING) throw new PermanentTaskException(
                "CHAT_MESSAGE_NOT_PENDING", "聊天问题已结束");
        var result = retrieval.retrieve(task.getUserId(), task.getCourseId(), question.getContent());
        if (!checkpoints.completed(task.getId(), TaskStage.CHAT_CONTEXT_RETRIEVED))
            checkpoints.complete(task.getId(), TaskStage.CHAT_CONTEXT_RETRIEVED, "{}");
        if (result.hits().isEmpty()) {
            var fallback = "当前课程资料不足以回答这个问题。请补充相关资料，或换一种更贴近资料内容的问法。";
            if (!checkpoints.completed(task.getId(), TaskStage.CHAT_ANSWER_GENERATED))
                checkpoints.complete(task.getId(), TaskStage.CHAT_ANSWER_GENERATED, "{}");
            coordinator.succeed(task.getUserId(), question.getId(), fallback, "[]", null);
            return;
        }
        var allowed = new LinkedHashSet<UUID>(); result.hits().forEach(hit -> allowed.add(hit.segment().getId()));
        final GeneratedChatAnswer generated;
        if (!checkpoints.completed(task.getId(), TaskStage.CHAT_ANSWER_GENERATED)) {
            var json = llm.generateJson(task.getId(), systemPrompt(), userPrompt(question, recent(task, question), result.hits()));
            generated = validate(json, allowed);
            checkpoints.complete(task.getId(), TaskStage.CHAT_ANSWER_GENERATED, json);
        } else generated = validate(checkpoints.resultJson(task.getId(), TaskStage.CHAT_ANSWER_GENERATED), allowed);
        var owned = segments.findRetrievableByIds(generated.sourceSegmentIds(), task.getUserId(), task.getCourseId());
        if (owned.size() != generated.sourceSegmentIds().size()) throw new PermanentTaskException(
                "CHAT_SOURCE_INVALID", "回答引用不属于当前用户或课程");
        var byId = new HashMap<UUID, ChatSourceRef>();
        owned.forEach(value -> byId.put(value.getId(), ChatSourceRef.from(value)));
        var refs = generated.sourceSegmentIds().stream().map(byId::get).toList();
        try { coordinator.succeed(task.getUserId(), question.getId(), generated.answer(),
                mapper.writeValueAsString(refs), generated.generalKnowledgeSupplement()); }
        catch (PermanentTaskException exception) { throw exception; }
        catch (Exception exception) { throw new PermanentTaskException("CHAT_SOURCE_INVALID", "回答来源无法保存"); }
    }

    private GeneratedChatAnswer validate(String json, Set<UUID> allowed) {
        try { return validator.parse(json, allowed); }
        catch (BusinessException exception) { throw new PermanentTaskException(exception.code(), exception.getMessage()); }
    }
    private List<ChatMessage> recent(BackgroundTask task, ChatMessage question) {
        var values = new ArrayList<>(messages.recentSucceeded(task.getUserId(), task.getCourseId(),
                question.getCreatedAt(), PageRequest.of(0, Math.max(1, properties.ai().chatHistoryLimit()))));
        Collections.reverse(values); return values;
    }
    private String systemPrompt() {
        return "你是课程问答助手。只返回 JSON；回答正文只能陈述课程上下文支持的内容。引用放入 sourceSegmentIds。任何模型常识必须仅放入 generalKnowledgeSupplement。";
    }
    private String userPrompt(ChatMessage question, List<ChatMessage> history,
                              List<com.finalweek.knowledge.RetrievalHit> hits) {
        var historyText = new StringBuilder();
        history.forEach(value -> historyText.append(value.getRole()).append(": ")
                .append(value.getContent().replace("\n", " ")).append('\n'));
        var context = new StringBuilder();
        hits.forEach(hit -> context.append("segmentId=").append(hit.segment().getId()).append("\n")
                .append(hit.segment().getContent()).append("\n---\n"));
        return """
                输出结构：{"answer":"基于课程资料的 Markdown 子集正文","sourceSegmentIds":["UUID"],"generalKnowledgeSupplement":"可选或 null"}
                最近对话：
                %s
                当前问题：%s
                本次课程上下文：
                %s
                sourceSegmentIds 只能引用上面的 segmentId。不要联网，不要虚构来源。
                """.formatted(historyText, question.getContent(), context);
    }
}
