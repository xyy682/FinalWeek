package com.finalweek.mockexam;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MockExamRepository extends JpaRepository<MockExam, UUID> {
    List<MockExam> findAllByCourseId(UUID courseId);
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update MockExam candidate set candidate.retryOf = null where candidate.retryOf.id = :examId")
    int detachRetriesOf(@Param("examId") UUID examId);
    Optional<MockExam> findByUserIdAndCourseIdAndIdempotencyKey(UUID userId, UUID courseId, String idempotencyKey);
    @Query("select exam from MockExam exam join com.finalweek.course.Course course on course.id = exam.courseId " +
            "where exam.id = :id and exam.userId = :userId and exam.deletedAt is null and course.deleted = false")
    Optional<MockExam> findOwned(@Param("id") UUID id, @Param("userId") UUID userId);
    @Query("select exam from MockExam exam join com.finalweek.course.Course course on course.id = exam.courseId " +
            "where exam.courseId = :courseId and exam.userId = :userId and exam.deletedAt is null " +
            "and course.deleted = false order by exam.createdAt desc, exam.id desc")
    Page<MockExam> pageOwned(@Param("userId") UUID userId, @Param("courseId") UUID courseId, Pageable pageable);
    @Query("select exam from MockExam exam where exam.courseId = :courseId and exam.deletedAt is null " +
            "and exam.status = 'SUCCEEDED' order by exam.createdAt desc")
    List<MockExam> findSucceededByCourseId(@Param("courseId") UUID courseId);
    @Query("select exam from MockExam exam join com.finalweek.course.Course course on course.id = exam.courseId " +
            "where course.deleted = true and exam.deletedAt is null")
    List<MockExam> findCleanupCandidatesForDeletedCourses(Pageable pageable);
    @Query("select exam from MockExam exam join com.finalweek.course.Course course on course.id = exam.courseId " +
            "where course.deleted = false and exam.deletedAt is null and exam.status = 'SUCCEEDED'")
    List<MockExam> findAllActiveWithFiles();
}
