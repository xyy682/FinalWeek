package com.finalweek.material;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity @Table(name = "course_segment", uniqueConstraints = @UniqueConstraint(columnNames = {"material_id", "chunk_no"}))
public class CourseSegment {
    @Id @UuidGenerator private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "course_id", nullable = false) private UUID courseId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "material_id") private Material material;
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String content;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 30) private SourceType sourceType;
    @Column(name = "page_number") private Integer pageNumber;
    @Column(name = "slide_number") private Integer slideNumber;
    @Column(name = "paragraph_number") private Integer paragraphNumber;
    @Column(name = "start_time_ms") private Long startTimeMs;
    @Column(name = "end_time_ms") private Long endTimeMs;
    @Lob @Column(name = "asr_text", columnDefinition = "TEXT") private String asrText;
    @Lob @Column(name = "ocr_text", columnDefinition = "TEXT") private String ocrText;
    @Column(name = "chunk_no", nullable = false) private int chunkNo;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected CourseSegment() {}
    public CourseSegment(UUID userId, UUID courseId, Material material, int chunkNo, ExtractedUnit unit) {
        this.userId = userId; this.courseId = courseId; this.material = material; this.chunkNo = chunkNo;
        this.content = unit.content(); this.sourceType = unit.sourceType(); this.pageNumber = unit.pageNumber();
        this.slideNumber = unit.slideNumber(); this.paragraphNumber = unit.paragraphNumber();
        this.startTimeMs = unit.startTimeMs(); this.endTimeMs = unit.endTimeMs();
        this.asrText = unit.asrText(); this.ocrText = unit.ocrText();
    }
    @PrePersist void created() { createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getCourseId() { return courseId; }
    public UUID getMaterialId() { return material.getId(); }
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
}
