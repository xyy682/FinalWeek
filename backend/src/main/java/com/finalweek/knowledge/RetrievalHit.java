package com.finalweek.knowledge;

import com.finalweek.material.CourseSegment;

public record RetrievalHit(CourseSegment segment, double rrfScore, Integer vectorRank, Integer bm25Rank) {}
