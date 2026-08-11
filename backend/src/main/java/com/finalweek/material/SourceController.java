package com.finalweek.material;

import com.finalweek.auth.FinalWeekPrincipal;
import com.finalweek.common.api.BusinessException;
import com.finalweek.upload.ObjectStorage;
import com.finalweek.upload.StorageProperties;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SourceController {
    private final MaterialService materials;
    private final CourseSegmentRepository segments;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;
    public SourceController(MaterialService materials, CourseSegmentRepository segments,
                            ObjectStorage storage, StorageProperties storageProperties) {
        this.materials = materials; this.segments = segments; this.storage = storage; this.storageProperties = storageProperties;
    }
    @GetMapping("/materials/{materialId}/preview")
    PreviewResponse preview(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID materialId) {
        var material = materials.get(principal.userId(), materialId);
        boolean normalizedPdf = material.getMediaType().equals("application/pdf")
                || material.getMediaType().contains("presentationml");
        String key = normalizedPdf ? material.getPreviewObjectKey() : material.getObjectKey();
        if (key == null) throw new BusinessException(HttpStatus.CONFLICT, "PREVIEW_NOT_READY", "资料预览尚未生成");
        var ttl = storageProperties.previewUrlTtl();
        var previewType = normalizedPdf ? "application/pdf" : material.getMediaType();
        boolean supportsTime = previewType.equals("audio/mpeg") || previewType.equals("video/mp4");
        return new PreviewResponse(storage.presignedGet(key, ttl), previewType, Instant.now().plus(ttl),
                normalizedPdf, supportsTime);
    }
    @GetMapping("/segments/{segmentId}")
    SegmentResponse segment(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID segmentId) {
        var segment = segments.findByIdAndUserId(segmentId, principal.userId()).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "SEGMENT_NOT_FOUND", "来源片段不存在或无权访问"));
        materials.get(principal.userId(), segment.getMaterialId());
        return new SegmentResponse(segment.getId(), segment.getMaterialId(), segment.getContent(),
                segment.getSourceType(), segment.getPageNumber(), segment.getSlideNumber(),
                segment.getParagraphNumber(), segment.getStartTimeMs(), segment.getEndTimeMs());
    }
    record PreviewResponse(String url, String mediaType, Instant expiresAt,
                           boolean normalizedPdf, boolean supportsTimeSeek) {}
    record SegmentResponse(UUID id, UUID materialId, String content, SourceType sourceType,
                           Integer pageNumber, Integer slideNumber, Integer paragraphNumber,
                           Long startTimeMs, Long endTimeMs) {}
}
