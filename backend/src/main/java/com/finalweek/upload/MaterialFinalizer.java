package com.finalweek.upload;

import com.finalweek.course.Course;
import com.finalweek.material.Material;
import com.finalweek.material.MaterialRepository;
import com.finalweek.task.BackgroundTask;
import com.finalweek.task.BackgroundTaskRepository;
import com.finalweek.task.TaskCheckpoint;
import com.finalweek.task.TaskCheckpointRepository;
import com.finalweek.task.TaskStage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialFinalizer {
    private final MaterialRepository materials;
    private final UploadCompletionRepository completions;
    private final BackgroundTaskRepository tasks;
    private final TaskCheckpointRepository checkpoints;

    public MaterialFinalizer(MaterialRepository materials, UploadCompletionRepository completions,
                             BackgroundTaskRepository tasks, TaskCheckpointRepository checkpoints) {
        this.materials = materials;
        this.completions = completions;
        this.tasks = tasks;
        this.checkpoints = checkpoints;
    }

    @Transactional(readOnly = true)
    public Optional<Material> completed(UUID uploadId) {
        return completions.findById(uploadId).map(UploadCompletion::getMaterial);
    }

    @Transactional(readOnly = true)
    public Optional<Material> duplicate(UUID courseId, String hash) {
        return materials.findByCourse_IdAndContentHashAndDeletedFalse(courseId, hash);
    }

    @Transactional(readOnly = true)
    public Optional<BackgroundTask> taskFor(Material material) { return tasks.findByMaterial_Id(material.getId()); }

    @Transactional
    public Material recordDuplicate(UUID uploadId, Material material) {
        completions.save(new UploadCompletion(uploadId, material));
        return material;
    }

    @Transactional
    public FinalizedMaterial create(UUID uploadId, UUID materialId, Course course, UploadMetadata metadata,
                                    String objectKey, String hash) {
        var material = materials.save(new Material(materialId, course, metadata.filename(), objectKey, hash,
                metadata.fileSize(), metadata.mediaType(), metadata.materialType(), metadata.focusNotes()));
        completions.save(new UploadCompletion(uploadId, material));
        materials.flush();
        var task = tasks.save(new BackgroundTask(metadata.userId(), metadata.courseId(), material));
        tasks.flush();
        checkpoints.save(new TaskCheckpoint(task, TaskStage.UPLOADED, objectKey, null));
        return new FinalizedMaterial(material, task);
    }

    public record FinalizedMaterial(Material material, BackgroundTask task) {}
}
