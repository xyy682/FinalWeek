package com.finalweek.plan;

import com.finalweek.common.persistence.BaseRepository;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;

public interface StudyPlanRepository extends BaseRepository<StudyPlan> {
    @Select("select * from study_plan where course_id = #{courseId}")
    Optional<StudyPlan> findByCourse_Id(UUID courseId);
}
