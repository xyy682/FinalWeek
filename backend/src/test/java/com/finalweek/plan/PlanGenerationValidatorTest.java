package com.finalweek.plan;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.api.BusinessException;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanGenerationValidatorTest {
    private final PlanGenerationValidator validator = new PlanGenerationValidator(new ObjectMapper().findAndRegisterModules());

    @Test
    void acceptsTasksWithinDailyAndTotalBudget() {
        var node = UUID.randomUUID(); var today = LocalDate.of(2026, 8, 11);
        var json = """
                {"tasks":[{"outlineNodeId":"%s","plannedDate":"2026-08-11","estimatedMinutes":30},
                {"outlineNodeId":"%s","plannedDate":"2026-08-12","estimatedMinutes":45}]}
                """.formatted(node, node);

        var result = validator.parse(json, Set.of(node), today, LocalDate.of(2026, 8, 13), 60);

        assertThat(result.tasks()).hasSize(2);
    }

    @Test
    void rejectsForeignNodeAndDailyOverflow() {
        var node = UUID.randomUUID(); var today = LocalDate.of(2026, 8, 11);
        var foreign = """
                {"tasks":[{"outlineNodeId":"%s","plannedDate":"2026-08-11","estimatedMinutes":20}]}
                """.formatted(UUID.randomUUID());
        assertThatThrownBy(() -> validator.parse(foreign, Set.of(node), today, LocalDate.of(2026, 8, 13), 60))
                .isInstanceOf(BusinessException.class);
        var overflow = """
                {"tasks":[{"outlineNodeId":"%s","plannedDate":"2026-08-11","estimatedMinutes":40},
                {"outlineNodeId":"%s","plannedDate":"2026-08-11","estimatedMinutes":30}]}
                """.formatted(node, node);
        assertThatThrownBy(() -> validator.parse(overflow, Set.of(node), today, LocalDate.of(2026, 8, 13), 60))
                .isInstanceOf(BusinessException.class);
    }
}
