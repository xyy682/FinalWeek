package com.finalweek.plan;

import com.finalweek.common.persistence.BaseRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

public interface PlanTaskRepository extends BaseRepository<PlanTask> {
    @Select("select * from plan_task where plan_id = #{planId} order by planned_date, position")
    List<PlanTask> findAllByPlan_IdOrderByPlannedDateAscPositionAsc(UUID planId);
    @Delete("delete from plan_task where plan_id = #{planId}")
    int deleteAllForPlan(UUID planId);
    @Select("select task.* from plan_task task join study_plan plan on plan.id = task.plan_id " +
            "join course on course.id = plan.course_id where task.id = #{id} and course.user_id = #{userId} " +
            "and course.deleted = false")
    Optional<PlanTask> findOwned(UUID id, UUID userId);
}
