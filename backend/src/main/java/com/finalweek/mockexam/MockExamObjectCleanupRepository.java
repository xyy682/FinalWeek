package com.finalweek.mockexam;

import com.finalweek.common.persistence.BaseRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;

public interface MockExamObjectCleanupRepository extends BaseRepository<MockExamObjectCleanup> {
    @Select("select * from mock_exam_object_cleanup where mock_exam_id = #{examId} for update")
    Optional<MockExamObjectCleanup> findByMockExamIdForUpdate(UUID examId);
    @Select("select * from mock_exam_object_cleanup where status = 'PENDING' and next_attempt_at &lt;= #{now} " +
            "order by next_attempt_at limit #{limit} for update")
    List<MockExamObjectCleanup> findDueForUpdate(Instant now, int limit);
}
