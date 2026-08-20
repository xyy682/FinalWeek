package com.finalweek.task;

import com.finalweek.common.api.BusinessException;
import com.finalweek.material.MaterialRepository;
import com.finalweek.material.MaterialStatus;
import com.finalweek.knowledgeversion.CourseKnowledgeVersionRepository;
import com.finalweek.course.CourseRepository;
import com.finalweek.plan.PlanRequestCoordinator;
import com.finalweek.chat.ChatCoordinator;
import com.finalweek.mockexam.MockExamCoordinator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskStateService {
    private final BackgroundTaskRepository tasks;
    private final MaterialRepository materials;
    private final FailedTaskRepository failedTasks;
    private final TaskCheckpointRepository checkpoints;
    private final TaskProgressService progress;
    private final CourseKnowledgeVersionRepository knowledgeVersions;
    private final CourseRepository courses;
    private final PlanRequestCoordinator planRequests;
    private final ChatCoordinator chats;
    private final MockExamCoordinator mockExams;

    public TaskStateService(BackgroundTaskRepository tasks, MaterialRepository materials,
                            FailedTaskRepository failedTasks, TaskCheckpointRepository checkpoints,
                            TaskProgressService progress, CourseKnowledgeVersionRepository knowledgeVersions,
                            CourseRepository courses, PlanRequestCoordinator planRequests, ChatCoordinator chats,
                            MockExamCoordinator mockExams) {
        this.tasks = tasks; this.materials = materials; this.failedTasks = failedTasks;
        this.checkpoints = checkpoints; this.progress = progress;
        this.knowledgeVersions = knowledgeVersions; this.courses = courses;
        this.planRequests = planRequests; this.chats = chats;
        this.mockExams = mockExams;
    }

    @Transactional(readOnly = true)
    public BackgroundTask owned(UUID userId, UUID taskId) {
        return tasks.findByIdAndUserId(taskId, userId).orElseThrow(() -> new BusinessException(
                HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在或无权访问"));
    }

    @Transactional(readOnly = true)
    public TaskProgressService.TaskView view(UUID userId, UUID taskId) {
        var task = owned(userId, taskId);
        var courseName = courses.findById(task.getCourseId()).map(com.finalweek.course.Course::getName)
                .orElse("已删除课程");
        return TaskProgressService.TaskView.from(task, courseName);
    }

    @Transactional
    public boolean queued(UUID id, int round) { return changed(tasks.markQueued(id, round), id, MaterialStatus.QUEUED); }

    @Transactional
    public boolean publishFailed(UUID id, int round, String code, String message) {
        if (tasks.markPublishFailed(id, round, code, truncate(message)) != 1) return false;
        var task = refresh(id, MaterialStatus.PUBLISH_FAILED);
        if (task.getTaskType() == TaskType.ANSWER_CHAT)
            chats.fail(task.getUserId(), task.getBusinessId(), code);
        return true;
    }

    @Transactional
    public boolean claim(UUID id, int round, int maximum) {
        return changed(tasks.claim(id, round, maximum), id, MaterialStatus.PROCESSING);
    }

    @Transactional
    public void retrying(UUID id, int round, String code, String message) {
        changed(tasks.markRetrying(id, round, code, truncate(message)), id, MaterialStatus.RETRYING);
    }

    @Transactional
    public void failed(UUID id, int round, String messageId, String code, String message) {
        if (tasks.markFailed(id, round, code, truncate(message)) == 1) {
            var task = tasks.findById(id).orElseThrow();
            if (task.getTaskType() == TaskType.PARSE_MATERIAL) {
                materials.updateStatus(task.getMaterialId(), MaterialStatus.FAILED);
            }
            if (task.getTaskType() == TaskType.GENERATE_OUTLINE)
                knowledgeVersions.findById(task.getBusinessId()).ifPresent(version -> {
                    version.fail(code); knowledgeVersions.save(version);
                });
            if (task.getTaskType() == TaskType.GENERATE_PLAN) planRequests.fail(task.getBusinessId(), code);
            if (task.getTaskType() == TaskType.ANSWER_CHAT) chats.fail(task.getUserId(), task.getBusinessId(), code);
            if (task.getTaskType() == TaskType.GENERATE_MOCK_EXAM) mockExams.fail(task.getBusinessId(), code, truncate(message));
            if (failedTasks.findByMessageId(messageId).isEmpty()) failedTasks.save(new FailedTask(task, messageId, truncate(message)));
            progress.publish(task);
        }
    }

    @Transactional
    public void succeeded(UUID id, int round) {
        if (tasks.markSucceeded(id, round) != 1) return;
        var task = tasks.findById(id).orElseThrow();
        if (checkpoints.findByTask_IdAndStage(id, TaskStage.COMPLETED).isEmpty()) {
            checkpoints.save(new TaskCheckpoint(task, TaskStage.COMPLETED, null, "{\"indexed\":true}"));
        }
        if (task.getTaskType() == TaskType.PARSE_MATERIAL) {
            materials.updateStatus(task.getMaterialId(), MaterialStatus.SUCCEEDED);
        }
        if (task.getTaskType() == TaskType.GENERATE_MOCK_EXAM) mockExams.state(task.getBusinessId(), TaskStatus.SUCCEEDED);
        failedTasks.findTopByTask_IdOrderByCreatedAtDesc(id).ifPresent(value -> {
            value.markResolved(); failedTasks.save(value);
        });
        progress.publish(task);
    }

    @Transactional
    public BackgroundTask cancel(UUID userId, UUID id) {
        owned(userId, id);
        if (tasks.cancelQueued(id) != 1) throw new BusinessException(HttpStatus.CONFLICT,
                "TASK_NOT_CANCELLABLE", "仅排队中的任务可以取消");
        var task = refresh(id, MaterialStatus.CANCELLED);
        if (task.getTaskType() == TaskType.GENERATE_OUTLINE)
            knowledgeVersions.findById(task.getBusinessId()).ifPresent(version -> {
                version.fail("TASK_CANCELLED"); knowledgeVersions.save(version);
            });
        if (task.getTaskType() == TaskType.GENERATE_PLAN) planRequests.fail(task.getBusinessId(), "TASK_CANCELLED");
        if (task.getTaskType() == TaskType.ANSWER_CHAT)
            chats.fail(task.getUserId(), task.getBusinessId(), "TASK_CANCELLED");
        if (task.getTaskType() == TaskType.GENERATE_MOCK_EXAM) mockExams.state(task.getBusinessId(), TaskStatus.CANCELLED);
        return task;
    }

    @Transactional
    public BackgroundTask prepareRepublish(UUID userId, UUID id) {
        var existing = owned(userId, id);
        if (tasks.prepareRepublish(id) != 1) throw new BusinessException(HttpStatus.CONFLICT,
                "TASK_NOT_REPUBLISHABLE", "仅发布失败的任务可以重新发布");
        if (existing.getTaskType() == TaskType.ANSWER_CHAT)
            chats.retryForTask(existing.getUserId(), existing.getBusinessId());
        return refresh(id, MaterialStatus.PENDING_PUBLISH);
    }

    @Transactional
    public BackgroundTask prepareManualRetry(UUID userId, UUID id) {
        var existing = owned(userId, id);
        if (existing.getTaskType() == TaskType.GENERATE_MOCK_EXAM) throw new BusinessException(HttpStatus.CONFLICT,
                "MOCK_EXAM_RETRY_REQUIRES_NEW_RECORD", "模拟卷重试必须保留原记录并创建新的关联记录");
        if (tasks.prepareManualRetry(id) != 1) throw new BusinessException(HttpStatus.CONFLICT,
                "TASK_NOT_RETRYABLE", "仅执行失败的任务可以人工重试");
        failedTasks.findTopByTask_IdOrderByCreatedAtDesc(id).ifPresent(value -> {
            value.markRedelivered(); failedTasks.save(value);
        });
        if (existing.getTaskType() == TaskType.GENERATE_OUTLINE)
            knowledgeVersions.findById(existing.getBusinessId()).ifPresent(version -> {
                version.retry(); knowledgeVersions.save(version);
            });
        if (existing.getTaskType() == TaskType.GENERATE_PLAN) planRequests.retry(existing.getBusinessId());
        if (existing.getTaskType() == TaskType.ANSWER_CHAT)
            chats.retryForTask(existing.getUserId(), existing.getBusinessId());
        return refresh(id, MaterialStatus.PENDING_PUBLISH);
    }

    @Transactional(readOnly = true)
    public List<TaskProgressService.TaskView> active(UUID userId) {
        var active = List.of(TaskStatus.PENDING_PUBLISH, TaskStatus.PUBLISH_FAILED, TaskStatus.QUEUED,
                TaskStatus.PROCESSING, TaskStatus.RETRYING);
        return tasks.findAllByUserIdAndVisibleInGlobalDrawerTrueAndStatusInOrderByUpdatedAtDesc(userId, active).stream()
                .map(task -> TaskProgressService.TaskView.from(task, courses.findById(task.getCourseId())
                        .map(com.finalweek.course.Course::getName).orElse("已删除课程"))).toList();
    }

    private boolean changed(int count, UUID id, MaterialStatus status) {
        if (count != 1) return false;
        refresh(id, status); return true;
    }
    private BackgroundTask refresh(UUID id, MaterialStatus status) {
        var task = tasks.findById(id).orElseThrow();
        if (task.getTaskType() == TaskType.PARSE_MATERIAL && task.getMaterialId() != null) {
            materials.updateStatus(task.getMaterialId(), status);
        }
        if (task.getTaskType() == TaskType.GENERATE_MOCK_EXAM) {
            var mapped = TaskStatus.valueOf(status.name());
            mockExams.state(task.getBusinessId(), mapped);
        }
        progress.publish(task);
        return task;
    }
    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
