package com.finalweek.ai;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public interface AsrClient {
    List<AsrSentence> recognize(UUID taskId, Path wavFile);
    record AsrSentence(long startTimeMs, long endTimeMs, String text) {}
}
