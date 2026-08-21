package com.finalweek.mockexam;

import com.finalweek.common.persistence.BaseRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface MockExamRepository extends BaseRepository<MockExam> {
    @Select("select * from mock_exam where course_id = #{courseId}")
    List<MockExam> findAllByCourseId(UUID courseId);
    @Update("update mock_exam set retry_of_id = null where retry_of_id = #{examId}")
    int detachRetriesOf(UUID examId);
    @Select("select * from mock_exam where user_id = #{userId} and course_id = #{courseId} " +
            "and idempotency_key = #{idempotencyKey}")
    Optional<MockExam> findByUserIdAndCourseIdAndIdempotencyKey(UUID userId, UUID courseId, String idempotencyKey);
    @Select("select exam.* from mock_exam exam join course on course.id = exam.course_id where exam.id = #{id} " +
            "and exam.user_id = #{userId} and exam.deleted_at is null and course.deleted = false")
    Optional<MockExam> findOwned(UUID id, UUID userId);
    @Select("select exam.* from mock_exam exam join course on course.id = exam.course_id " +
            "where exam.course_id = #{courseId} and exam.user_id = #{userId} and exam.deleted_at is null " +
            "and course.deleted = false order by exam.created_at desc, exam.id desc limit #{limit} offset #{offset}")
    List<MockExam> pageOwned(UUID userId, UUID courseId, long offset, int limit);
    @Select("select count(*) from mock_exam exam join course on course.id = exam.course_id " +
            "where exam.course_id = #{courseId} and exam.user_id = #{userId} and exam.deleted_at is null " +
            "and course.deleted = false")
    long countOwned(UUID userId, UUID courseId);
    @Select("select * from mock_exam where course_id = #{courseId} and deleted_at is null " +
            "and status = 'SUCCEEDED' order by created_at desc")
    List<MockExam> findSucceededByCourseId(UUID courseId);
    @Select("select exam.* from mock_exam exam join course on course.id = exam.course_id " +
            "where course.deleted = true and exam.deleted_at is null limit #{limit}")
    List<MockExam> findCleanupCandidatesForDeletedCourses(int limit);
    @Select("select exam.* from mock_exam exam join course on course.id = exam.course_id " +
            "where course.deleted = false and exam.deleted_at is null and exam.status = 'SUCCEEDED'")
    List<MockExam> findAllActiveWithFiles();
}
