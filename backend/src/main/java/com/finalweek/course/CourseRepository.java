package com.finalweek.course;

import com.finalweek.common.persistence.BaseRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;

public interface CourseRepository extends BaseRepository<Course> {
    @Select("select * from course where user_id = #{userId} and deleted = false order by updated_at desc")
    List<Course> findAllByUserIdAndDeletedFalseOrderByUpdatedAtDesc(UUID userId);
    @Select("select * from course where id = #{id} and user_id = #{userId} and deleted = false")
    Optional<Course> findByIdAndUserIdAndDeletedFalse(UUID id, UUID userId);
    @Select("select count(*) from course where user_id = #{userId} and deleted = false")
    long countByUserIdAndDeletedFalse(UUID userId);
    @Select("select * from course where deleted = false")
    List<Course> findAllByDeletedFalse();
    @Select("select * from course where deleted = true")
    List<Course> findAllByDeletedTrue();
    @Select("select * from course where id = #{id} and user_id = #{userId} and deleted = false for update")
    Optional<Course> findOwnedByIdForUpdate(UUID id, UUID userId);
}
