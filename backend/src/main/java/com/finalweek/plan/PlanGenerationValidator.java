package com.finalweek.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.api.BusinessException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PlanGenerationValidator {
    private final ObjectMapper mapper;
    public PlanGenerationValidator(ObjectMapper mapper) { this.mapper = mapper; }

    public GeneratedPlan parse(String json, Set<UUID> allowedNodeIds, LocalDate today,
                               LocalDate examDate, int dailyMinutes) {
        final GeneratedPlan value;
        try { value = mapper.readValue(json, GeneratedPlan.class); }
        catch (Exception exception) { throw invalid("计划模型返回的 JSON 格式不正确"); }
        if (value.tasks() == null || value.tasks().isEmpty() || value.tasks().size() > 500) {
            throw invalid("计划任务数量不正确");
        }
        var dailyTotals = new HashMap<LocalDate, Integer>();
        long total = 0;
        for (var task : value.tasks()) {
            if (task == null || task.outlineNodeId() == null || !allowedNodeIds.contains(task.outlineNodeId())) {
                throw invalid("计划包含不属于当前提纲的知识点");
            }
            if (task.plannedDate() == null || task.plannedDate().isBefore(today)
                    || !task.plannedDate().isBefore(examDate)) {
                throw invalid("计划日期必须位于今天到考试日前一天之间");
            }
            if (task.estimatedMinutes() <= 0 || task.estimatedMinutes() > dailyMinutes) {
                throw invalid("单项预计用时不正确");
            }
            var dayTotal = dailyTotals.merge(task.plannedDate(), task.estimatedMinutes(), Integer::sum);
            if (dayTotal > dailyMinutes) throw invalid("单日任务超过每日可用时间");
            total += task.estimatedMinutes();
        }
        var available = ChronoUnit.DAYS.between(today, examDate) * (long) dailyMinutes;
        if (total > available) throw invalid("总预计时间超过考试前可用时间");
        return value;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_GATEWAY, "PLAN_FORMAT_INVALID", message);
    }
}
