package com.finalweek.material;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseSegmentRepository extends JpaRepository<CourseSegment, UUID> {
    Optional<CourseSegment> findByIdAndUserId(UUID id, UUID userId);
    List<CourseSegment> findAllByMaterial_IdOrderByChunkNo(UUID materialId);
    void deleteAllByMaterial_Id(UUID materialId);
}
