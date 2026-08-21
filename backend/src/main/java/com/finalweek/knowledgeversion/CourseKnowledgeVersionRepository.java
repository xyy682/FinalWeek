package com.finalweek.knowledgeversion;

import com.finalweek.common.persistence.BaseRepository;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;

public interface CourseKnowledgeVersionRepository extends BaseRepository<CourseKnowledgeVersion> {
    @Select("select * from course_knowledge_version where course_id = #{courseId} and material_set_hash = #{materialSetHash}")
    Optional<CourseKnowledgeVersion> findByCourse_IdAndMaterialSetHash(UUID courseId, String materialSetHash);
    @Select("select * from course_knowledge_version where course_id = #{courseId} and status = #{status}")
    Optional<CourseKnowledgeVersion> findByCourse_IdAndStatus(UUID courseId, KnowledgeVersionStatus status);
    @Select("select * from course_knowledge_version where course_id = #{courseId} order by version desc limit 1")
    Optional<CourseKnowledgeVersion> findFirstByCourse_IdOrderByVersionDesc(UUID courseId);
    @Select("select * from course_knowledge_version where id = #{id} for update")
    Optional<CourseKnowledgeVersion> findByIdForUpdate(UUID id);
}
