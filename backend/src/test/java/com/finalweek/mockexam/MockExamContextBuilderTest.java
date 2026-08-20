package com.finalweek.mockexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.knowledge.HybridRetrievalService;
import com.finalweek.knowledgeversion.KnowledgeVersionService;
import com.finalweek.material.CourseSegment;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.material.MaterialRepository;
import com.finalweek.material.SourceType;
import com.finalweek.outline.OutlineImportance;
import com.finalweek.outline.OutlineNode;
import com.finalweek.outline.OutlineSourceRef;
import java.util.*;
import org.junit.jupiter.api.Test;

class MockExamContextBuilderTest {
    @Test void selectedOutlineScopeNeverAddsCourseWideRetrievalHits() throws Exception {
        var retrieval = mock(HybridRetrievalService.class); var versions = mock(KnowledgeVersionService.class);
        var segments = mock(CourseSegmentRepository.class); var materials = mock(MaterialRepository.class);
        var mapper = new ObjectMapper(); var exam = mock(MockExam.class); var node = mock(OutlineNode.class);
        var segment = mock(CourseSegment.class);
        var userId = UUID.randomUUID(); var courseId = UUID.randomUUID(); var versionId = UUID.randomUUID();
        var materialId = UUID.randomUUID(); var segmentId = UUID.randomUUID(); var nodeId = UUID.randomUUID();
        when(exam.getUserId()).thenReturn(userId); when(exam.getCourseId()).thenReturn(courseId);
        when(exam.getKnowledgeVersionId()).thenReturn(versionId);
        when(node.getId()).thenReturn(nodeId); when(node.getImportance()).thenReturn(OutlineImportance.HIGH);
        when(node.getPosition()).thenReturn(0); when(node.getTitle()).thenReturn("限定知识点");
        when(node.getSourceRefsJson()).thenReturn(mapper.writeValueAsString(List.of(
                new OutlineSourceRef(segmentId, SourceType.TEXT_PARAGRAPH, null, null, 1, null, null))));
        when(versions.materialIds(versionId)).thenReturn(List.of(materialId));
        when(segments.findRetrievableByIds(anyList(), eq(userId), eq(courseId))).thenReturn(List.of(segment));
        when(segment.getId()).thenReturn(segmentId); when(segment.getMaterialId()).thenReturn(materialId);
        when(segment.getContent()).thenReturn("只属于所选知识点的课程片段");
        when(materials.findAllByCourse_IdAndDeletedFalse(courseId)).thenReturn(List.of());
        var request = new MockExamRequestNormalizer.Normalized(MockExamScope.OUTLINE_NODES, List.of(nodeId),
                Map.of(MockExamQuestionType.SINGLE_CHOICE, 1), ScoreMode.AUTO, Map.of(), null, null,
                false, "", 1, 2);
        var builder = new MockExamContextBuilder(retrieval, versions, segments, materials, mapper);

        var context = builder.build(new MockExamCoordinator.Work(exam, request, List.of(node)));

        assertThat(context.segmentIds()).containsExactly(segmentId);
        verifyNoInteractions(retrieval);
    }
}
