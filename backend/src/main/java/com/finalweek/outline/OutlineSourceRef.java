package com.finalweek.outline;

import com.finalweek.material.CourseSegment;
import com.finalweek.material.SourceType;
import java.util.UUID;

public record OutlineSourceRef(UUID segmentId, SourceType sourceType, Integer pageNumber, Integer slideNumber,
                               Integer paragraphNumber, Long startTimeMs, Long endTimeMs) {
    public static OutlineSourceRef from(CourseSegment segment) {
        return new OutlineSourceRef(segment.getId(), segment.getSourceType(), segment.getPageNumber(),
                segment.getSlideNumber(), segment.getParagraphNumber(), segment.getStartTimeMs(),
                segment.getEndTimeMs());
    }
}
