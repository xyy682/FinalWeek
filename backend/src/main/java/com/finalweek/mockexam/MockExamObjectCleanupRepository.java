package com.finalweek.mockexam;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MockExamObjectCleanupRepository extends JpaRepository<MockExamObjectCleanup, UUID> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select cleanup from MockExamObjectCleanup cleanup where cleanup.mockExam.id = :examId")
    Optional<MockExamObjectCleanup> findByMockExamIdForUpdate(@Param("examId") UUID examId);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select cleanup from MockExamObjectCleanup cleanup where cleanup.status = 'PENDING' " +
            "and cleanup.nextAttemptAt <= :now order by cleanup.nextAttemptAt")
    List<MockExamObjectCleanup> findDueForUpdate(@Param("now") Instant now, Pageable pageable);
}
