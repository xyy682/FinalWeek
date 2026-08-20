package com.finalweek.mockexam;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface MockExamQuestionSourceRepository extends JpaRepository<MockExamQuestionSource, MockExamQuestionSourceId> {
    List<MockExamQuestionSource> findAllByQuestionId(UUID questionId);
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MockExamQuestionSource source where source.questionId in " +
            "(select question.id from MockExamQuestion question where question.mockExam.courseId = :courseId)")
    int deleteAllByCourseId(@Param("courseId") UUID courseId);
}
