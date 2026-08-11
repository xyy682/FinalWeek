package com.finalweek.material;

import java.util.List;
import java.util.UUID;

public record CourseContext(UUID userId, UUID courseId, UUID materialId, String previewObjectKey,
                            Long durationMs, List<String> warnings, List<ExtractedUnit> units) {
    public CourseContext {
        warnings = List.copyOf(warnings); units = List.copyOf(units);
    }
}
