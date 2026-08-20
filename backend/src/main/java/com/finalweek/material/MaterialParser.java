package com.finalweek.material;

import com.finalweek.task.BackgroundTask;
import java.nio.file.Path;

public interface MaterialParser {
    boolean supports(String mediaType);
    ExtractionResult extract(BackgroundTask task, Material material, Path source, Path workDirectory);
}
