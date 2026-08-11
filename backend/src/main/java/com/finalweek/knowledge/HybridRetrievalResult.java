package com.finalweek.knowledge;

import java.util.List;

public record HybridRetrievalResult(List<RetrievalHit> hits, boolean vectorDegraded, boolean bm25Degraded) {
    public HybridRetrievalResult { hits = List.copyOf(hits); }
}
