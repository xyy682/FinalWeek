package com.finalweek.outline;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

public interface OutlineNodeRepository extends JpaRepository<OutlineNode, UUID> {
    List<OutlineNode> findAllByOutline_IdOrderByPosition(UUID outlineId);
    @Query("select node from OutlineNode node join node.outline outline join outline.course course " +
            "where node.id = :id and course.user.id = :userId and course.deleted = false")
    Optional<OutlineNode> findOwned(@Param("id") UUID id, @Param("userId") UUID userId);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from OutlineNode node where node.outline.id = :outlineId")
    int deleteAllForOutline(@Param("outlineId") UUID outlineId);
}
