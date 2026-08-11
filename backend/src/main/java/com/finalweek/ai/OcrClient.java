package com.finalweek.ai;

import java.nio.file.Path;
import java.util.UUID;

public interface OcrClient { String recognize(UUID taskId, Path image); }
