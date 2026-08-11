package com.finalweek.ai;

import java.util.List;
import java.util.UUID;

public interface EmbeddingClient {
    List<float[]> embedDocuments(UUID taskId, List<String> texts);
    float[] embedQuery(String query);
}
