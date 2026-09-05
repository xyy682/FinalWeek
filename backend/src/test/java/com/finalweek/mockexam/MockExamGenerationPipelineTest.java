package com.finalweek.mockexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.ai.LlmClient;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.task.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MockExamGenerationPipelineTest {
    @Test void retriesOnlyTheFailedQuestionTypeBatch() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var coordinator = mock(MockExamCoordinator.class);
        var contexts = mock(MockExamContextBuilder.class);
        var publisher = mock(MockExamPublisher.class);
        var exams = mock(MockExamRepository.class);
        var questions = mock(MockExamQuestionRepository.class);
        var segments = mock(CourseSegmentRepository.class);
        var checkpoints = mock(TaskCheckpointService.class);
        var artifacts = mock(MockExamArtifactGenerator.class);
        var llm = mock(LlmClient.class);
        var properties = new MockExamProperties(3, Duration.ofMinutes(4), 50, 1000, 300, 2000, 10,
                .82, "v1", 8);
        var validator = new MockExamValidator(mapper, new MockExamFormulaValidator(), new MockExamScoreAllocator());
        var pipeline = new MockExamGenerationPipeline(coordinator, contexts, validator, new MockExamQualityChecker(),
                publisher, exams, questions, segments, checkpoints, artifacts, llm, mapper, properties);

        var taskId = UUID.randomUUID(); var examId = UUID.randomUUID(); var userId = UUID.randomUUID();
        var courseId = UUID.randomUUID(); var sourceId = UUID.randomUUID();
        var task = mock(BackgroundTask.class); when(task.getId()).thenReturn(taskId); when(task.getBusinessId()).thenReturn(examId);
        when(task.getUserId()).thenReturn(userId); when(task.getCourseId()).thenReturn(courseId);
        var exam = mock(MockExam.class); when(exam.getId()).thenReturn(examId);
        when(exam.getQualityPolicyVersion()).thenReturn("v1"); when(exam.getHistorySimilarityThreshold()).thenReturn(.82);
        var counts = new EnumMap<MockExamQuestionType, Integer>(MockExamQuestionType.class);
        counts.put(MockExamQuestionType.SINGLE_CHOICE, 1); counts.put(MockExamQuestionType.ESSAY, 1);
        var request = new MockExamRequestNormalizer.Normalized(MockExamScope.WHOLE_COURSE, List.of(), counts,
                ScoreMode.AUTO, Map.of(), null, null, false, "", 2, 17);
        var work = new MockExamCoordinator.Work(exam, request, List.of());
        var context = new MockExamContextBuilder.Context(List.of(sourceId), Map.of(), false, false,
                "segmentId=" + sourceId + "\n数据库事务与并发控制课程内容");
        when(coordinator.work(examId)).thenReturn(work); when(contexts.build(work)).thenReturn(context);
        when(checkpoints.completed(eq(taskId), any())).thenAnswer(invocation -> {
            var stage = invocation.getArgument(1, TaskStage.class);
            return stage == TaskStage.PAPER_VALIDATED || stage == TaskStage.PDFS_GENERATED;
        });
        when(segments.findRetrievableByIds(anyList(), eq(userId), eq(courseId))).thenReturn(List.of());
        when(exams.findSucceededByCourseId(courseId)).thenReturn(List.of());

        var essayCalls = new AtomicInteger();
        when(llm.generateJson(eq(taskId), anyString(), anyString(), eq(properties.requestTimeout())))
                .thenAnswer(invocation -> {
                    var prompt = invocation.getArgument(2, String.class);
                    if (prompt.contains("questionType 必须全部为 SINGLE_CHOICE"))
                        return json(mapper, choice(sourceId));
                    if (essayCalls.getAndIncrement() == 0) return json(mapper, essay(sourceId, null));
                    return json(mapper, essay(sourceId, "从事务原子性、隔离性和并发异常分点作答"));
                });
        var generatedCheckpoint = new AtomicReference<String>();
        doAnswer(invocation -> { if (invocation.getArgument(1) == TaskStage.QUESTIONS_GENERATED)
                generatedCheckpoint.set(invocation.getArgument(2)); return null; })
                .when(checkpoints).complete(eq(taskId), any(), anyString());

        pipeline.execute(task);

        verify(llm, times(3)).generateJson(eq(taskId), anyString(), anyString(), eq(properties.requestTimeout()));
        assertThat(essayCalls).hasValue(2);
        var generated = mapper.readValue(generatedCheckpoint.get(), GeneratedMockExam.class);
        assertThat(generated.questions()).extracting(GeneratedMockExam.Question::questionType)
                .containsExactly(MockExamQuestionType.SINGLE_CHOICE, MockExamQuestionType.ESSAY);
        assertThat(generated.questions()).extracting(GeneratedMockExam.Question::score).containsExactly(2, 15);
    }

    private String json(ObjectMapper mapper, GeneratedMockExam.Question question) throws Exception {
        return mapper.writeValueAsString(new GeneratedMockExam(List.of(question)));
    }
    private GeneratedMockExam.Question choice(UUID source) {
        return new GeneratedMockExam.Question(MockExamQuestionType.SINGLE_CHOICE, "事务的原子性是指什么？",
                List.of("全部成功或全部回滚", "并发事务互不干扰", "提交结果永久保存", "事务可嵌套执行"),
                new GeneratedMockExam.Answer(List.of(0), null, null, null, null, null), 99, false,
                List.of(source), List.of());
    }
    private GeneratedMockExam.Question essay(UUID source, String answer) {
        return new GeneratedMockExam.Question(MockExamQuestionType.ESSAY, "论述事务隔离级别与并发异常的关系。",
                List.of("模型误填的非选择题选项"), new GeneratedMockExam.Answer(null, null, null, answer, null, null),
                99, false, List.of(source), List.of());
    }
}