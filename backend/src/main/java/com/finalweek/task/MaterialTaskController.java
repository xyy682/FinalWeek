package com.finalweek.task;

import com.finalweek.auth.FinalWeekPrincipal;
import com.finalweek.common.api.BusinessException;
import com.finalweek.material.MaterialService;
import com.finalweek.task.TaskProgressService.TaskView;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MaterialTaskController {
    private final MaterialService materials;
    private final ParseTaskRepository tasks;
    private final TaskDispatchService dispatcher;
    public MaterialTaskController(MaterialService materials, ParseTaskRepository tasks, TaskDispatchService dispatcher) {
        this.materials = materials; this.tasks = tasks; this.dispatcher = dispatcher;
    }
    @PostMapping("/materials/{materialId}/retry")
    TaskView retry(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID materialId) {
        var material = materials.get(principal.userId(), materialId);
        var task = tasks.findByMaterial_Id(material.getId()).orElseThrow(() -> new BusinessException(
                HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "资料解析任务不存在"));
        return TaskView.from(dispatcher.manualRetry(principal.userId(), task.getId()));
    }
    @PostMapping("/failed-tasks/{taskId}/redeliver")
    TaskView redeliver(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID taskId) {
        return TaskView.from(dispatcher.manualRetry(principal.userId(), taskId));
    }
}
