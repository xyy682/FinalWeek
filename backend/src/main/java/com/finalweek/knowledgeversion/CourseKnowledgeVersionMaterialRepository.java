package com.finalweek.knowledgeversion;

import java.util.Collection;
import com.finalweek.common.persistence.RepositoryMarker;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

public interface CourseKnowledgeVersionMaterialRepository extends RepositoryMarker {
    @Insert("insert into course_knowledge_version_material(knowledge_version_id, material_id, position) " +
            "values(#{knowledgeVersionId}, #{materialId}, #{position})")
    int insert(CourseKnowledgeVersionMaterial value);

    default CourseKnowledgeVersionMaterial save(CourseKnowledgeVersionMaterial value) {
        insert(value);
        return value;
    }

    default CourseKnowledgeVersionMaterial saveAndFlush(CourseKnowledgeVersionMaterial value) {
        return save(value);
    }

    default List<CourseKnowledgeVersionMaterial> saveAll(Collection<CourseKnowledgeVersionMaterial> values) {
        values.forEach(this::insert);
        return List.copyOf(values);
    }

    default void flush() {}

    @Select("select * from course_knowledge_version_material where knowledge_version_id = #{knowledgeVersionId} " +
            "order by position")
    List<CourseKnowledgeVersionMaterial> findAllByKnowledgeVersionIdOrderByPosition(UUID knowledgeVersionId);
}
