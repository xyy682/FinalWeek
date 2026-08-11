package com.finalweek.course;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRepository extends JpaRepository<Course, UUID> {

    List<Course> findAllByUserIdAndDeletedFalseOrderByUpdatedAtDesc(UUID userId);

    Optional<Course> findByIdAndUserIdAndDeletedFalse(UUID id, UUID userId);

    long countByUserIdAndDeletedFalse(UUID userId);
    List<Course> findAllByDeletedFalse();
    List<Course> findAllByDeletedTrue();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select course from Course course where course.id = :id and course.user.id = :userId and course.deleted = false")
    Optional<Course> findOwnedByIdForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);
}
