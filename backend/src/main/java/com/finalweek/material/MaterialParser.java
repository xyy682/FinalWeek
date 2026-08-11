package com.finalweek.material;

import com.finalweek.task.ParseTask;
import java.nio.file.Path;

public interface MaterialParser {
    boolean supports(String mediaType);
    ExtractionResult extract(ParseTask task, Material material, Path source, Path workDirectory);
}
