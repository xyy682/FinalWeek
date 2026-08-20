package com.finalweek.plan;

import com.finalweek.common.persistence.BaseRepository;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;

public interface PlanGenerationRequestRepository extends BaseRepository<PlanGenerationRequest> {
    @Select("select * from plan_generation_request where user_id = #{userId} and course_id = #{courseId} " +
            "and idempotency_key = #{key} for update")
    Optional<PlanGenerationRequest> findForUpdate(UUID userId, UUID courseId, String key);
}
