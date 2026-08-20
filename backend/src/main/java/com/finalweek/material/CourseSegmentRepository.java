package com.finalweek.material;

import com.finalweek.common.persistence.BaseRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

public interface CourseSegmentRepository extends BaseRepository<CourseSegment> {
    @Select("select * from course_segment where id = #{id} and user_id = #{userId}")
    Optional<CourseSegment> findByIdAndUserId(UUID id, UUID userId);
    @Select("select * from course_segment where material_id = #{materialId} order by chunk_no")
    List<CourseSegment> findAllByMaterial_IdOrderByChunkNo(UUID materialId);
    @Delete("delete from course_segment where material_id = #{materialId}")
    void deleteAllByMaterial_Id(UUID materialId);
    default List<CourseSegment> findRetrievableByIds(List<UUID> ids, UUID userId, UUID courseId) {
        return ids.isEmpty() ? List.of() : selectRetrievableByIds(ids, userId, courseId);
    }
    @Select({"<script>", "select segment.* from course_segment segment join material on material.id = segment.material_id ",
            "join course on course.id = material.course_id where segment.user_id = #{userId} ",
            "and segment.course_id = #{courseId} and material.status = 'SUCCEEDED' ",
            "and material.deleted = false and course.deleted = false and segment.id in ",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>", "</script>"})
    List<CourseSegment> selectRetrievableByIds(List<UUID> ids, UUID userId, UUID courseId);
    @Select("select segment.* from course_segment segment join material on material.id = segment.material_id " +
            "join course on course.id = material.course_id where segment.user_id = #{userId} " +
            "and segment.course_id = #{courseId} and material.status = 'SUCCEEDED' " +
            "and material.deleted = false and course.deleted = false order by material.id, segment.chunk_no")
    List<CourseSegment> findAllRetrievable(UUID userId, UUID courseId);
    @Select("select segment.* from course_segment segment join material on material.id = segment.material_id " +
            "join course on course.id = material.course_id where segment.course_id = #{courseId} " +
            "and material.status = 'SUCCEEDED' and material.deleted = false and course.deleted = false " +
            "order by material.id, segment.chunk_no")
    List<CourseSegment> findAllRetrievableByCourseId(UUID courseId);
    @Select("select distinct segment.course_id from course_segment segment join material on material.id = segment.material_id " +
            "join course on course.id = material.course_id where material.status = 'SUCCEEDED' " +
            "and material.deleted = false and course.deleted = false")
    List<UUID> findCourseIdsWithRetrievableSegments();
    @Select("select segment.* from course_segment segment join material on material.id = segment.material_id " +
            "join course on course.id = material.course_id where material.deleted = false " +
            "and course.deleted = false and material.status = 'SUCCEEDED'")
    List<CourseSegment> findAllActive();
}
