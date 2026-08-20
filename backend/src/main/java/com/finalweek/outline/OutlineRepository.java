package com.finalweek.outline;

import com.finalweek.common.persistence.BaseRepository;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;

public interface OutlineRepository extends BaseRepository<Outline> {
    @Select("select * from outline where knowledge_version_id = #{knowledgeVersionId}")
    Optional<Outline> findByKnowledgeVersion_Id(UUID knowledgeVersionId);
    @Select("select outline.* from outline join course_knowledge_version version " +
            "on version.id = outline.knowledge_version_id where outline.course_id = #{courseId} " +
            "and version.status = 'PUBLISHED'")
    Optional<Outline> findCurrentByCourseId(UUID courseId);
}
