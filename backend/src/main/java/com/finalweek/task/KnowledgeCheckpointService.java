package com.finalweek.task;

import com.finalweek.knowledge.SegmentChunk;
import com.finalweek.material.CourseContext;
import com.finalweek.material.CourseSegment;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.material.MaterialRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeCheckpointService {
    private final ParseTaskRepository tasks;
    private final TaskCheckpointRepository checkpoints;
    private final MaterialRepository materials;
    private final CourseSegmentRepository segments;
    private final TaskProgressService progress;

    public KnowledgeCheckpointService(ParseTaskRepository tasks, TaskCheckpointRepository checkpoints,
                                      MaterialRepository materials, CourseSegmentRepository segments,
                                      TaskProgressService progress) {
        this.tasks = tasks; this.checkpoints = checkpoints; this.materials = materials;
        this.segments = segments; this.progress = progress;
    }

    @Transactional(readOnly = true)
    public boolean completed(UUID taskId, TaskStage stage) {
        return checkpoints.findByTask_IdAndStage(taskId, stage).isPresent();
    }

    @Transactional
    public void chunked(UUID taskId, CourseContext context, List<SegmentChunk> chunks) {
        if (completed(taskId, TaskStage.CHUNKED)) return;
        var task = tasks.findById(taskId).orElseThrow();
        var material = materials.findById(context.materialId()).orElseThrow();
        segments.deleteAllByMaterial_Id(material.getId());
        segments.flush();
        for (int index = 0; index < chunks.size(); index++) {
            var chunk = chunks.get(index);
            segments.save(new CourseSegment(context.userId(), context.courseId(), material, index,
                    chunk.tokenCount(), chunk.unit()));
        }
        checkpoints.save(new TaskCheckpoint(task, TaskStage.CHUNKED, null,
                "{\"chunkCount\":" + chunks.size() + "}"));
        tasks.advanceStage(taskId, TaskStage.CHUNKED);
        progress.publish(tasks.findById(taskId).orElseThrow());
    }

    @Transactional
    public void embeddingCompleted(UUID taskId, int pointCount) {
        if (completed(taskId, TaskStage.EMBEDDING_COMPLETED)) return;
        var task = tasks.findById(taskId).orElseThrow();
        checkpoints.save(new TaskCheckpoint(task, TaskStage.EMBEDDING_COMPLETED, null,
                "{\"pointCount\":" + pointCount + ",\"luceneCommitted\":true}"));
        tasks.advanceStage(taskId, TaskStage.EMBEDDING_COMPLETED);
        progress.publish(tasks.findById(taskId).orElseThrow());
    }
}
