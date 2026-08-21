package com.finalweek.material;

import com.finalweek.common.persistence.BeforeInsert;
import com.finalweek.common.persistence.BeforeUpdate;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class CourseSegment {
    private UUID id;
    private UUID userId;
    private UUID courseId;
    private UUID materialId;
    private String content;
    private SourceType sourceType;
    private Integer pageNumber;
    private Integer slideNumber;
    private Integer paragraphNumber;
    private Long startTimeMs;
    private Long endTimeMs;
    private String asrText;
    private String ocrText;
    private int chunkNo;
    private int tokenCount;
    private Instant createdAt;
    protected CourseSegment() {}
    public CourseSegment(UUID userId, UUID courseId, Material material, int chunkNo, ExtractedUnit unit) {
        this.id = stableId(material.getId(), chunkNo);
        this.userId = userId; this.courseId = courseId; this.materialId = material.getId();
        this.chunkNo = chunkNo;
        this.content = unit.content(); this.sourceType = unit.sourceType(); this.pageNumber = unit.pageNumber();
        this.slideNumber = unit.slideNumber(); this.paragraphNumber = unit.paragraphNumber();
        this.startTimeMs = unit.startTimeMs(); this.endTimeMs = unit.endTimeMs();
        this.asrText = unit.asrText(); this.ocrText = unit.ocrText();
    }
    public CourseSegment(UUID userId, UUID courseId, Material material, int chunkNo, int tokenCount,
                         ExtractedUnit unit) {
        this(userId, courseId, material, chunkNo, unit);
        this.tokenCount = tokenCount;
    }
    public static UUID stableId(UUID materialId, int chunkNo) {
        return UUID.nameUUIDFromBytes(("course-segment:" + materialId + ":" + chunkNo)
                .getBytes(StandardCharsets.UTF_8));
    }
    @BeforeInsert void created() { createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getCourseId() { return courseId; }
    public UUID getMaterialId() { return materialId; }
    public String getContent() { return content; }
    public SourceType getSourceType() { return sourceType; }
    public Integer getPageNumber() { return pageNumber; }
    public Integer getSlideNumber() { return slideNumber; }
    public Integer getParagraphNumber() { return paragraphNumber; }
    public Long getStartTimeMs() { return startTimeMs; }
    public Long getEndTimeMs() { return endTimeMs; }
    public String getAsrText() { return asrText; }
    public String getOcrText() { return ocrText; }
    public int getChunkNo() { return chunkNo; }
    public int getTokenCount() { return tokenCount; }
}
