package com.finalweek.mockexam;

import com.finalweek.common.persistence.BaseRepository;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;

public interface MockExamQuestionRepository extends BaseRepository<MockExamQuestion> {
    @Select("select * from mock_exam_question where mock_exam_id = #{mockExamId} order by position")
    List<MockExamQuestion> findAllByMockExam_IdOrderByPosition(UUID mockExamId);
}
