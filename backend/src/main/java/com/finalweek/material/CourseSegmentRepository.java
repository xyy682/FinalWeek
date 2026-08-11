package com.finalweek.material;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseSegmentRepository extends JpaRepository<CourseSegment, UUID> {
    Optional<CourseSegment> findByIdAndUserId(UUID id, UUID userId);
    List<CourseSegment> findAllByMaterial_IdOrderByChunkNo(UUID materialId);
    void deleteAllByMaterial_Id(UUID materialId);

    @Query("select segment from CourseSegment segment join segment.material material " +
            "where segment.id in :ids and segment.userId = :userId and segment.courseId = :courseId " +
            "and material.status = 'SUCCEEDED' and material.deleted = false and material.course.deleted = false")
    List<CourseSegment> findRetrievableByIds(@Param("ids") List<UUID> ids, @Param("userId") UUID userId,
                                              @Param("courseId") UUID courseId);

    @Query("select segment from CourseSegment segment join segment.material material " +
            "where segment.userId = :userId and segment.courseId = :courseId " +
            "and material.status = 'SUCCEEDED' and material.deleted = false and material.course.deleted = false " +
            "order by material.id, segment.chunkNo")
    List<CourseSegment> findAllRetrievable(@Param("userId") UUID userId, @Param("courseId") UUID courseId);

    @Query("select segment from CourseSegment segment join segment.material material " +
            "where segment.courseId = :courseId and material.status = 'SUCCEEDED' and material.deleted = false " +
            "and material.course.deleted = false order by material.id, segment.chunkNo")
    List<CourseSegment> findAllRetrievableByCourseId(@Param("courseId") UUID courseId);

    @Query("select distinct segment.courseId from CourseSegment segment join segment.material material " +
            "where material.status = 'SUCCEEDED' and material.deleted = false and material.course.deleted = false")
    List<UUID> findCourseIdsWithRetrievableSegments();
}
