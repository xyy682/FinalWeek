package com.finalweek.outline;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutlineRepository extends JpaRepository<Outline, UUID> {
    Optional<Outline> findByCourse_Id(UUID courseId);
}
