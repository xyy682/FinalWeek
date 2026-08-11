package com.finalweek.outline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.task.*;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutlineCheckpointService {
    private final ParseTaskRepository tasks;
    private final TaskCheckpointRepository checkpoints;
    private final ObjectMapper mapper;
    public OutlineCheckpointService(ParseTaskRepository tasks, TaskCheckpointRepository checkpoints, ObjectMapper mapper) {
        this.tasks = tasks; this.checkpoints = checkpoints; this.mapper = mapper;
    }
    public boolean completed(UUID taskId, TaskStage stage) {
        return checkpoints.findByTask_IdAndStage(taskId, stage).isPresent();
    }
    public OutlineContext context(UUID taskId) {
        return read(taskId, TaskStage.CONTEXT_RETRIEVED, OutlineContext.class);
    }
    public String generatedJson(UUID taskId) {
        return checkpoints.findByTask_IdAndStage(taskId, TaskStage.OUTLINE_GENERATED)
                .map(TaskCheckpoint::getResultJson).orElseThrow();
    }
    @Transactional
    public void contextRetrieved(UUID taskId, OutlineContext context) {
        save(taskId, TaskStage.CONTEXT_RETRIEVED, write(context));
    }
    @Transactional
    public void outlineGenerated(UUID taskId, String json) {
        save(taskId, TaskStage.OUTLINE_GENERATED, json);
    }
    private void save(UUID taskId, TaskStage stage, String json) {
        if (completed(taskId, stage)) return;
        var task = tasks.findById(taskId).orElseThrow();
        if (task.getTaskType() != TaskType.GENERATE_OUTLINE) throw new PermanentTaskException(
                "CHECKPOINT_TASK_TYPE_INVALID", "提纲 checkpoint 不能写入资料解析任务");
        checkpoints.save(new TaskCheckpoint(task, stage, null, json));
        tasks.advanceStage(taskId, stage);
    }
    private <T> T read(UUID taskId, TaskStage stage, Class<T> type) {
        try { return mapper.readValue(checkpoints.findByTask_IdAndStage(taskId, stage)
                .map(TaskCheckpoint::getResultJson).orElseThrow(), type); }
        catch (Exception exception) { throw new PermanentTaskException("OUTLINE_CHECKPOINT_INVALID", "提纲 checkpoint 无法读取"); }
    }
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    public record OutlineContext(List<UUID> segmentIds, String prompt) {
        public OutlineContext { segmentIds = List.copyOf(segmentIds); }
    }
}
