package com.finalweek.outline;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.ai.LlmClient;
import com.finalweek.knowledge.HybridRetrievalResult;
import com.finalweek.knowledge.HybridRetrievalService;
import com.finalweek.knowledge.RetrievalHit;
import com.finalweek.material.CourseSegment;
import com.finalweek.material.MaterialRepository;
import com.finalweek.task.BackgroundTask;
import com.finalweek.task.PermanentTaskException;
import com.finalweek.task.TaskStage;
import com.finalweek.knowledgeversion.KnowledgeVersionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutlineGenerationPipelineTest {
    @Test
    void repairsInvalidJsonExactlyOnceThenPublishesValidatedSources() {
        var fixture = fixture();
        when(fixture.llm.generateJson(any(), anyString(), anyString()))
                .thenReturn("{}", fixture.validJson);

        fixture.pipeline.execute(fixture.task);

        verify(fixture.llm, times(2)).generateJson(any(), anyString(), anyString());
        verify(fixture.checkpoints).outlineGenerated(isNull(), contains("Newton second law"));
        verify(fixture.publisher).publish(eq(fixture.task), any(GeneratedOutline.class), eq(java.util.Set.of(fixture.segmentId)));
    }

    @Test
    void secondInvalidResponseFailsWithoutReplacingCurrentOutline() {
        var fixture = fixture();
        when(fixture.llm.generateJson(any(), anyString(), anyString())).thenReturn("{}", "{\"nodes\":[]}");

        assertThatThrownBy(() -> fixture.pipeline.execute(fixture.task))
                .isInstanceOfSatisfying(PermanentTaskException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("OUTLINE_FORMAT_INVALID"));
        verify(fixture.llm, times(2)).generateJson(any(), anyString(), anyString());
        verifyNoInteractions(fixture.publisher);
    }

    private Fixture fixture() {
        var retrieval = mock(HybridRetrievalService.class);
        var materials = mock(MaterialRepository.class);
        var segments = mock(com.finalweek.material.CourseSegmentRepository.class);
        when(segments.findAllByMaterial_IdOrderByChunkNo(any())).thenReturn(List.of());
        var checkpoints = mock(OutlineCheckpointService.class);
        var validator = new OutlineGenerationValidator(new ObjectMapper());
        var publisher = mock(OutlinePublisher.class);
        var llm = mock(LlmClient.class);
        var segment = mock(CourseSegment.class);
        var segmentId = UUID.randomUUID();
        when(segment.getId()).thenReturn(segmentId);
        when(segment.getContent()).thenReturn("Force equals mass times acceleration.");
        var userId = UUID.randomUUID(); var courseId = UUID.randomUUID();
        var knowledgeVersionId = UUID.randomUUID();
        var task = new BackgroundTask(userId, courseId, knowledgeVersionId, 1);
        var knowledgeVersions = mock(KnowledgeVersionService.class);
        when(knowledgeVersions.materialIds(knowledgeVersionId)).thenReturn(List.of(UUID.randomUUID()));
        var context = new OutlineCheckpointService.OutlineContext(List.of(segmentId), "course context JSON request");
        when(checkpoints.completed(null, TaskStage.CONTEXT_RETRIEVED)).thenReturn(false);
        when(checkpoints.completed(null, TaskStage.OUTLINE_GENERATED)).thenReturn(false);
        when(checkpoints.context(null)).thenReturn(context);
        when(retrieval.retrieve(eq(userId), eq(courseId), anyString(), anySet())).thenReturn(new HybridRetrievalResult(
                List.of(new RetrievalHit(segment, 1, 1, 1)), false, false));
        var pipeline = new OutlineGenerationPipeline(retrieval, materials, segments, checkpoints, validator,
                publisher, llm, new ObjectMapper(), knowledgeVersions);
        var valid = """
                {"nodes":[{"title":"Newton second law","importance":"HIGH","sourceSegmentIds":["%s"],"children":[]}]}
                """.formatted(segmentId);
        return new Fixture(pipeline, llm, checkpoints, publisher, task, segmentId, valid);
    }
    private record Fixture(OutlineGenerationPipeline pipeline, LlmClient llm, OutlineCheckpointService checkpoints,
                           OutlinePublisher publisher, BackgroundTask task, UUID segmentId, String validJson) {}
}
