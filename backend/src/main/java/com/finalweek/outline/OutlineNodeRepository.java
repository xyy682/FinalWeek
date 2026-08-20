package com.finalweek.outline;

import com.finalweek.common.persistence.BaseRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

public interface OutlineNodeRepository extends BaseRepository<OutlineNode> {
    @Select("select * from outline_node where outline_id = #{outlineId} order by position")
    List<OutlineNode> findAllByOutline_IdOrderByPosition(UUID outlineId);
    @Select("select node.* from outline_node node join outline on outline.id = node.outline_id " +
            "join course on course.id = outline.course_id where node.id = #{id} and course.user_id = #{userId} " +
            "and course.deleted = false")
    Optional<OutlineNode> findOwned(UUID id, UUID userId);
    @Delete("delete from outline_node where outline_id = #{outlineId}")
    int deleteAllForOutline(UUID outlineId);
}
