package com.finalweek.knowledge;

import java.util.UUID;

public record VectorPoint(UUID segmentId, UUID userId, UUID courseId, UUID materialId, float[] vector) {}
