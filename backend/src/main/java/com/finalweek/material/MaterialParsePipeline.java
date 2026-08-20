package com.finalweek.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.knowledge.KnowledgeIndexer;
import com.finalweek.knowledge.SemanticChunker;
import com.finalweek.task.ExtractionCheckpointService;
import com.finalweek.task.KnowledgeCheckpointService;
import com.finalweek.task.BackgroundTask;
import com.finalweek.task.PermanentTaskException;
import com.finalweek.task.RetryableTaskException;
import com.finalweek.task.TaskPipeline;
import com.finalweek.upload.ObjectStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class MaterialParsePipeline implements TaskPipeline {
    private static final Logger log = LoggerFactory.getLogger(MaterialParsePipeline.class);
    private final MaterialRepository materials;
    private final List<MaterialParser> parsers;
    private final ObjectStorage storage;
    private final ObjectMapper mapper;
    private final ExtractionCheckpointService checkpoints;
    private final KnowledgeCheckpointService knowledgeCheckpoints;
    private final SemanticChunker chunker;
    private final KnowledgeIndexer indexer;
    private final CourseSegmentRepository segments;
    public MaterialParsePipeline(MaterialRepository materials, List<MaterialParser> parsers, ObjectStorage storage,
                                 ObjectMapper mapper, ExtractionCheckpointService checkpoints,
                                 KnowledgeCheckpointService knowledgeCheckpoints, SemanticChunker chunker,
                                 KnowledgeIndexer indexer, CourseSegmentRepository segments) {
        this.materials = materials; this.parsers = parsers; this.storage = storage;
        this.mapper = mapper; this.checkpoints = checkpoints; this.knowledgeCheckpoints = knowledgeCheckpoints;
        this.chunker = chunker; this.indexer = indexer; this.segments = segments;
    }
    @Override public com.finalweek.task.TaskType type() { return com.finalweek.task.TaskType.PARSE_MATERIAL; }
    @Override public void execute(BackgroundTask task) {
        boolean extractionDone = checkpoints.completed(task.getId());
        timed(task, "CONTENT_EXTRACTED", extractionDone, () -> { if (!extractionDone) extract(task); });
        var context = loadContext(task);
        boolean chunkingDone = knowledgeCheckpoints.completed(task.getId(), com.finalweek.task.TaskStage.CHUNKED);
        timed(task, "CHUNKED", chunkingDone, () -> { if (!chunkingDone) {
            var chunks = chunker.chunk(context);
            if (chunks.isEmpty()) throw new PermanentTaskException("CONTENT_EMPTY", "资料没有可建立索引的正文");
            knowledgeCheckpoints.chunked(task.getId(), context, chunks);
        }});
        boolean indexingDone = knowledgeCheckpoints.completed(task.getId(), com.finalweek.task.TaskStage.EMBEDDING_COMPLETED);
        timed(task, "EMBEDDING_COMPLETED", indexingDone, () -> { if (!indexingDone) {
            var chunkedSegments = segments.findAllByMaterial_IdOrderByChunkNo(task.getMaterialId());
            indexer.index(task, chunkedSegments);
            knowledgeCheckpoints.embeddingCompleted(task.getId(), chunkedSegments.size());
        }});
    }

    private void timed(BackgroundTask task, String stage, boolean skipped, Runnable action) {
        long started = System.nanoTime(); action.run();
        log.info("Parse stage observed taskId={} stage={} durationMs={} skippedFromCheckpoint={}", task.getId(),
                stage, (System.nanoTime() - started) / 1_000_000, skipped);
    }

    private void extract(BackgroundTask task) {
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
        } catch (PermanentTaskException | RetryableTaskException exception) { throw exception; }
        catch (Exception exception) { throw new RetryableTaskException("EXTRACTION_IO_FAILED", "资料提取过程暂时失败"); }
        finally { if (work != null) deleteTree(work); }
    }

    private CourseContext loadContext(BackgroundTask task) {
        try (var input = storage.get(checkpoints.contextObjectKey(task.getId()))) {
            return mapper.readValue(input, CourseContext.class);
        } catch (PermanentTaskException | RetryableTaskException exception) { throw exception; }
        catch (Exception exception) { throw new RetryableTaskException("CHECKPOINT_READ_FAILED", "内容提取 checkpoint 暂时无法读取"); }
    }
    private void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }
}
