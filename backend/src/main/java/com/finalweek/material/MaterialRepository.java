package com.finalweek.material;

import com.finalweek.common.persistence.BaseRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface MaterialRepository extends BaseRepository<Material> {
    @Select("select material.* from material join course on course.id = material.course_id " +
            "where material.course_id = #{courseId} and course.user_id = #{userId} and material.deleted = false " +
            "order by material.created_at desc")
    List<Material> findAllByCourse_IdAndCourse_User_IdAndDeletedFalseOrderByCreatedAtDesc(UUID courseId, UUID userId);
    @Select("select * from material where course_id = #{courseId} and deleted = false")
    List<Material> findAllByCourse_IdAndDeletedFalse(UUID courseId);
    @Select("select material.* from material join course on course.id = material.course_id " +
            "where material.id = #{id} and course.user_id = #{userId} and material.deleted = false")
    Optional<Material> findByIdAndCourse_User_IdAndDeletedFalse(UUID id, UUID userId);
    @Select("select * from material where course_id = #{courseId} and content_hash = #{contentHash} and deleted = false")
    Optional<Material> findByCourse_IdAndContentHashAndDeletedFalse(UUID courseId, String contentHash);
    @Select("select count(*) from material where course_id = #{courseId} and deleted = false")
    long countByCourse_IdAndDeletedFalse(UUID courseId);
    @Select("select * from material where course_id = #{courseId} and deleted = false order by updated_at desc limit 1")
    Optional<Material> findTopByCourse_IdAndDeletedFalseOrderByUpdatedAtDesc(UUID courseId);
    @Select("select material.* from material join course on course.id = material.course_id " +
            "where material.deleted = false and course.deleted = false")
    List<Material> findAllActive();
    @Update("update material set status = #{status}, updated_at = current_timestamp where id = #{id}")
    int updateStatus(UUID id, MaterialStatus status);
    @Select("select material.* from material join course on course.id = material.course_id " +
            "where material.id = #{id} and course.user_id = #{userId} and material.deleted = false for update")
    Optional<Material> findOwnedByIdForUpdate(UUID id, UUID userId);
}
