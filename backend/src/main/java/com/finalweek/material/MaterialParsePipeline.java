package com.finalweek.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.task.ExtractionCheckpointService;
import com.finalweek.task.ParseTask;
import com.finalweek.task.PermanentTaskException;
import com.finalweek.task.RetryableTaskException;
import com.finalweek.task.TaskPipeline;
import com.finalweek.upload.ObjectStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MaterialParsePipeline implements TaskPipeline {
    private final MaterialRepository materials;
    private final List<MaterialParser> parsers;
    private final ObjectStorage storage;
    private final ObjectMapper mapper;
    private final ExtractionCheckpointService checkpoints;
    public MaterialParsePipeline(MaterialRepository materials, List<MaterialParser> parsers, ObjectStorage storage,
                                 ObjectMapper mapper, ExtractionCheckpointService checkpoints) {
        this.materials = materials; this.parsers = parsers; this.storage = storage;
        this.mapper = mapper; this.checkpoints = checkpoints;
    }
    @Override public void execute(ParseTask task) {
        if (checkpoints.completed(task.getId())) throw nextStage();
        Path work = null;
        try {
            var material = materials.findById(task.getMaterialId()).orElseThrow(() ->
                    new PermanentTaskException("MATERIAL_NOT_FOUND", "解析资料不存在"));
            var parser = parsers.stream().filter(value -> value.supports(material.getMediaType())).findFirst()
                    .orElseThrow(() -> new PermanentTaskException("FORMAT_UNSUPPORTED", "不支持的资料格式"));
            work = Files.createTempDirectory("finalweek-parse-");
            var extension = switch (material.getMediaType()) {
                case "application/pdf" -> ".pdf"; case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
                case "audio/mpeg" -> ".mp3"; case "video/mp4" -> ".mp4"; default -> ".txt";
            };
            var source = work.resolve("source" + extension);
            try (var input = storage.get(material.getObjectKey())) { Files.copy(input, source); }
            var result = parser.extract(task, material, source, work);
            String previewKey = null;
            if (result.previewFile() != null) {
                previewKey = "derived/" + material.getId() + "/preview.pdf";
                storage.put(previewKey, result.previewFile(), "application/pdf");
            }
            var context = new CourseContext(task.getUserId(), task.getCourseId(), material.getId(), previewKey,
                    result.durationMs(), result.warnings(), result.units());
            var contextFile = work.resolve("content-extracted.json");
            mapper.writeValue(contextFile.toFile(), context);
            var contextKey = "derived/" + material.getId() + "/content-extracted.json";
            storage.put(contextKey, contextFile, "application/json");
            checkpoints.complete(task.getId(), context, contextKey);
            throw nextStage();
        } catch (PermanentTaskException | RetryableTaskException exception) { throw exception; }
        catch (Exception exception) { throw new RetryableTaskException("EXTRACTION_IO_FAILED", "资料提取过程暂时失败"); }
        finally { if (work != null) deleteTree(work); }
    }
    private RetryableTaskException nextStage() {
        return new RetryableTaskException("NEXT_STAGE_NOT_READY", "内容提取已完成，等待阶段 6 建立索引");
    }
    private void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }
}
