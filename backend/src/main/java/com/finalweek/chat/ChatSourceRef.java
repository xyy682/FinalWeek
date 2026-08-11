package com.finalweek.chat;

import com.finalweek.material.CourseSegment;
import com.finalweek.material.SourceType;
import java.util.UUID;

public record ChatSourceRef(UUID segmentId, SourceType sourceType, Integer pageNumber, Integer slideNumber,
                            Integer paragraphNumber, Long startTimeMs, Long endTimeMs) {
    static ChatSourceRef from(CourseSegment segment) {
        return new ChatSourceRef(segment.getId(), segment.getSourceType(), segment.getPageNumber(),
                segment.getSlideNumber(), segment.getParagraphNumber(), segment.getStartTimeMs(),
                segment.getEndTimeMs());
    }
}
