package com.finalweek.mockexam;
import com.finalweek.common.persistence.RepositoryMarker;
import java.util.*;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
public interface MockExamQuestionSourceRepository extends RepositoryMarker {
    @Insert("insert into mock_exam_question_source(question_id, segment_id) values(#{questionId}, #{segmentId})")
    int insert(MockExamQuestionSource value);
    default MockExamQuestionSource save(MockExamQuestionSource value) { insert(value); return value; }
    @Select("select * from mock_exam_question_source where question_id = #{questionId}")
    List<MockExamQuestionSource> findAllByQuestionId(UUID questionId);
    @Delete("delete source from mock_exam_question_source source join mock_exam_question question " +
            "on question.id = source.question_id where question.mock_exam_id in " +
            "(select id from mock_exam where course_id = #{courseId})")
    int deleteAllByCourseId(UUID courseId);
}
