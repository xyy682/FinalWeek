package com.finalweek.task;

import com.finalweek.material.CourseContext;
import com.finalweek.material.CourseSegment;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.material.MaterialRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExtractionCheckpointService {
    private final BackgroundTaskRepository tasks;
    private final TaskCheckpointRepository checkpoints;
    private final MaterialRepository materials;
    private final CourseSegmentRepository segments;
    private final TaskProgressService progress;
    public ExtractionCheckpointService(BackgroundTaskRepository tasks, TaskCheckpointRepository checkpoints,
                                       MaterialRepository materials, CourseSegmentRepository segments,
                                       TaskProgressService progress) {
        this.tasks = tasks; this.checkpoints = checkpoints; this.materials = materials;
        this.segments = segments; this.progress = progress;
    }
    @Transactional(readOnly = true)
    public boolean completed(UUID taskId) {
        return checkpoints.findByTask_IdAndStage(taskId, TaskStage.CONTENT_EXTRACTED).isPresent();
    }
    @Transactional(readOnly = true)
    public String contextObjectKey(UUID taskId) {
        return checkpoints.findByTask_IdAndStage(taskId, TaskStage.CONTENT_EXTRACTED)
                .map(TaskCheckpoint::getResultObjectKey).orElseThrow();
    }
    @Transactional
    public void complete(UUID taskId, CourseContext context, String objectKey) {
        if (checkpoints.findByTask_IdAndStage(taskId, TaskStage.CONTENT_EXTRACTED).isPresent()) return;
        var task = tasks.findById(taskId).orElseThrow();
        var material = materials.findById(context.materialId()).orElseThrow();
        segments.deleteAllByMaterial_Id(material.getId());
        int chunk = 0;
        for (var unit : context.units()) {
            segments.save(new CourseSegment(context.userId(), context.courseId(), material, chunk++, unit));
        }
        var warning = context.warnings().isEmpty() ? null : String.join("；", context.warnings());
        material.extracted(context.previewObjectKey(), context.durationMs(), truncate(warning));
        checkpoints.save(new TaskCheckpoint(task, TaskStage.CONTENT_EXTRACTED, objectKey,
                "{\"unitCount\":" + context.units().size() + ",\"warningCount\":" + context.warnings().size() + "}"));
        tasks.advanceStage(taskId, TaskStage.CONTENT_EXTRACTED);
        progress.publish(tasks.findById(taskId).orElseThrow());
    }
    private String truncate(String value) { return value == null || value.length() <= 1000 ? value : value.substring(0, 1000); }
}
