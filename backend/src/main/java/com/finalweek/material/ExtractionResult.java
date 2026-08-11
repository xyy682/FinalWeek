package com.finalweek.material;

import java.nio.file.Path;
import java.util.List;

public record ExtractionResult(List<ExtractedUnit> units, List<String> warnings, Path previewFile, Long durationMs) {
    public ExtractionResult { units = List.copyOf(units); warnings = List.copyOf(warnings); }
}
