package com.finalweek.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finalweek.course.Course;
import com.finalweek.material.Material;
import com.finalweek.material.MaterialRepository;
import com.finalweek.material.MaterialType;
import com.finalweek.task.BackgroundTask;
import com.finalweek.task.BackgroundTaskRepository;
import com.finalweek.task.TaskCheckpoint;
import com.finalweek.task.TaskCheckpointRepository;
import com.finalweek.task.TaskStage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MaterialFinalizerTest {
    @Test
    void createsUploadedCheckpointInSameFinalizationTransaction() {
        var materials = mock(MaterialRepository.class);
        var completions = mock(UploadCompletionRepository.class);
        var tasks = mock(BackgroundTaskRepository.class);
        var checkpoints = mock(TaskCheckpointRepository.class);
        when(materials.save(any(Material.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tasks.save(any(BackgroundTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var finalizer = new MaterialFinalizer(materials, completions, tasks, checkpoints);
        var userId = UUID.randomUUID();
        var courseId = UUID.randomUUID();
        var uploadId = UUID.randomUUID();
        var metadata = new UploadMetadata(uploadId, userId, courseId, "notes.txt", 20, 1024, 1,
                "0".repeat(64), "txt", "text/plain", MaterialType.NOTES, null, Instant.now().plusSeconds(60));

        finalizer.create(uploadId, UUID.randomUUID(), mock(Course.class), metadata,
                "materials/course/material/original", metadata.expectedSha256());

        var checkpoint = ArgumentCaptor.forClass(TaskCheckpoint.class);
        verify(checkpoints).save(checkpoint.capture());
        assertThat(checkpoint.getValue().getStage()).isEqualTo(TaskStage.UPLOADED);
        assertThat(checkpoint.getValue().getResultObjectKey()).isEqualTo("materials/course/material/original");
    }
}
