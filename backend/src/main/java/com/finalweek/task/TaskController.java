package com.finalweek.task;

import com.finalweek.auth.FinalWeekPrincipal;
import com.finalweek.task.TaskProgressService.TaskView;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final TaskStateService states;
    private final TaskDispatchService dispatcher;
    private final TaskProgressService progress;
    public TaskController(TaskStateService states, TaskDispatchService dispatcher, TaskProgressService progress) {
        this.states = states; this.dispatcher = dispatcher; this.progress = progress;
    }
    @GetMapping("/{taskId}") TaskView get(@AuthenticationPrincipal FinalWeekPrincipal principal,
                                          @PathVariable UUID taskId) {
        return TaskView.from(states.owned(principal.userId(), taskId));
    }
    @PostMapping("/{taskId}/cancel") TaskView cancel(@AuthenticationPrincipal FinalWeekPrincipal principal,
                                                     @PathVariable UUID taskId) {
        return TaskView.from(states.cancel(principal.userId(), taskId));
    }
    @PostMapping("/{taskId}/republish") TaskView republish(@AuthenticationPrincipal FinalWeekPrincipal principal,
                                                           @PathVariable UUID taskId) {
        return TaskView.from(dispatcher.republish(principal.userId(), taskId));
    }
    @GetMapping(value = "/events", produces = "text/event-stream")
    SseEmitter events(@AuthenticationPrincipal FinalWeekPrincipal principal) {
        return progress.subscribe(principal.userId());
    }
}
