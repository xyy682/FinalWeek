package com.finalweek.plan;

import com.finalweek.ai.LlmClient;
import com.finalweek.common.api.BusinessException;
import com.finalweek.task.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PlanGenerationPipeline implements TaskPipeline {
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Shanghai");
    private final PlanRequestCoordinator coordinator;
    private final PlanGenerationValidator validator;
    private final TaskCheckpointService checkpoints;
    private final LlmClient llm;

    public PlanGenerationPipeline(PlanRequestCoordinator coordinator, PlanGenerationValidator validator,
                                  TaskCheckpointService checkpoints, LlmClient llm) {
        this.coordinator = coordinator; this.validator = validator; this.checkpoints = checkpoints; this.llm = llm;
    }

    @Override public TaskType type() { return TaskType.GENERATE_PLAN; }

    @Override public void execute(BackgroundTask task) {
        var work = coordinator.work(task.getBusinessId());
        if (!checkpoints.completed(task.getId(), TaskStage.PLAN_CONTEXT_RETRIEVED))
            checkpoints.complete(task.getId(), TaskStage.PLAN_CONTEXT_RETRIEVED, "{}");
        var allowed = new LinkedHashSet<UUID>(); work.nodes().forEach(node -> allowed.add(node.id()));
        var today = LocalDate.ofInstant(work.request().getCreatedAt(), USER_ZONE);
        final GeneratedPlan generated;
        if (!checkpoints.completed(task.getId(), TaskStage.PLAN_GENERATED)) {
            var json = llm.generateJson(task.getId(), systemPrompt(), userPrompt(work.input(), today, work.nodes()));
            generated = validate(json, allowed, today, work.input());
            checkpoints.complete(task.getId(), TaskStage.PLAN_GENERATED, json);
        } else generated = validate(checkpoints.resultJson(task.getId(), TaskStage.PLAN_GENERATED),
                allowed, today, work.input());
        coordinator.publish(task.getBusinessId(), work.input(), generated);
    }

    private GeneratedPlan validate(String json, Set<UUID> allowed, LocalDate today,
                                   PlanRequestCoordinator.PlanInput input) {
        try { return validator.parse(json, allowed, today, input.examDate(), input.dailyMinutes()); }
        catch (BusinessException exception) { throw new PermanentTaskException(exception.code(), exception.getMessage()); }
    }
    private String systemPrompt() {
        return "你是严谨的复习计划生成器。必须只返回 JSON 对象，严格符合提供的字段；只能使用给定提纲节点 ID。";
    }
    private String userPrompt(PlanRequestCoordinator.PlanInput input, LocalDate today,
                              List<PlanRequestCoordinator.Node> nodes) {
        var nodeLines = new StringBuilder();
        nodes.forEach(node -> nodeLines.append(node.id()).append(" | ").append(node.importance())
                .append(" | ").append(node.title().replace("\n", " ")).append('\n'));
        return """
                请生成简单每日复习计划 JSON：
                {"tasks":[{"outlineNodeId":"UUID","plannedDate":"YYYY-MM-DD","estimatedMinutes":整数}]}
                今天：%s；考试日期：%s（任务最晚只能到考试日前一天）；每日最多：%d 分钟；
                整体掌握程度：%s；目标成绩：%d。
                高重要度与较低掌握程度应分配更多时间；每个日期的总分钟数不得超过每日上限。
                只能从以下知识版本提纲节点选择：
                %s
                """.formatted(today, input.examDate(), input.dailyMinutes(), input.masteryLevel(),
                input.targetScore(), nodeLines);
    }
}
