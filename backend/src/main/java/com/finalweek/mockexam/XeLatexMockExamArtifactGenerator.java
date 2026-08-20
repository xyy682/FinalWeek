package com.finalweek.mockexam;

import com.finalweek.task.*;
import com.finalweek.upload.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class XeLatexMockExamArtifactGenerator implements MockExamArtifactGenerator {
    private static final Logger log = LoggerFactory.getLogger(XeLatexMockExamArtifactGenerator.class);
    private final MockExamTexRenderer renderer;
    private final MockExamPdfProperties properties;
    private final ObjectStorage storage;
    private final MockExamCleanupService cleanup;
    public XeLatexMockExamArtifactGenerator(MockExamTexRenderer renderer, MockExamPdfProperties properties,
                                            ObjectStorage storage, MockExamCleanupService cleanup) {
        this.renderer = renderer; this.properties = properties; this.storage = storage; this.cleanup = cleanup;
    }
    @Override public Artifacts generate(UUID taskId, MockExam exam, GeneratedMockExam generated) {
        if (!Objects.equals(exam.getPdfTemplateVersion(), properties.templateVersion())
                || !Objects.equals(exam.getFormulaPolicyVersion(), properties.formulaPolicyVersion()))
            throw new PermanentTaskException("MOCK_EXAM_PDF_POLICY_VERSION_UNAVAILABLE",
                    "任务创建时的 PDF 模板或公式策略版本已不可用，请从失败记录创建新的重试");
        var root = properties.tempDirectory().toAbsolutePath().normalize(); Path directory = null;
        var prefix = "users/" + exam.getUserId() + "/courses/" + exam.getCourseId() + "/mock-exams/" + exam.getId();
        var paperKey = prefix + "/paper.pdf"; var answerKey = prefix + "/answer.pdf";
        boolean paperUploaded = false; boolean answerUploaded = false;
        try {
            Files.createDirectories(root);
            directory = Files.createTempDirectory(root, "exam-" + taskId + "-").toAbsolutePath().normalize();
            if (!directory.startsWith(root)) throw new SecurityException("临时目录越界");
            var documents = renderer.render(exam, generated);
            var paperTex = directory.resolve("paper.tex"); var answerTex = directory.resolve("answer.tex");
            Files.writeString(paperTex, documents.paperTex(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.writeString(answerTex, documents.answerTex(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            var paperPdf = compile(directory, paperTex); var answerPdf = compile(directory, answerTex);
            validateLocal(paperPdf); validateLocal(answerPdf);
            storage.put(paperKey, paperPdf, "application/pdf"); paperUploaded = true;
            storage.put(answerKey, answerPdf, "application/pdf"); answerUploaded = true;
            validateRemote(paperKey); validateRemote(answerKey);
            return new Artifacts(paperKey, answerKey);
        } catch (PermanentTaskException | RetryableTaskException exception) { throw exception;
        } catch (StorageOperationException exception) {
            if (!cleanupObjects(paperUploaded, answerUploaded, paperKey, answerKey)) {
                cleanup.enqueue(exam, paperKey, answerKey);
                throw new PermanentTaskException("MOCK_EXAM_PARTIAL_CLEANUP_PENDING",
                        "模拟卷部分文件清理尚未完成，请从失败记录创建新的重试");
            }
            throw new RetryableTaskException("MOCK_EXAM_PDF_STORAGE_FAILED", "模拟卷 PDF 保存或复核失败");
        } catch (Exception exception) {
            if ((paperUploaded || answerUploaded)
                    && !cleanupObjects(paperUploaded, answerUploaded, paperKey, answerKey))
                cleanup.enqueue(exam, paperKey, answerKey);
            log.warn("Mock exam PDF generation failed", exception);
            throw new PermanentTaskException("MOCK_EXAM_PDF_FAILED", "模拟卷 PDF 生成失败");
        } finally { if (directory != null) deleteTree(directory, root); }
    }
    private Path compile(Path directory, Path tex) throws Exception {
        for (int pass = 0; pass < 2; pass++) {
            var process = new ProcessBuilder(properties.executable(), "-no-shell-escape", "-halt-on-error",
                    "-interaction=nonstopmode", "-output-directory=" + directory, tex.toString())
                    .directory(directory.toFile()).redirectErrorStream(true).start();
            var log = new BoundedLog(Math.toIntExact(properties.maxLogSize().toBytes()));
            var reader = Thread.ofVirtual().name("xelatex-log-" + tex.getFileName()).start(() -> {
                try (var input = process.getInputStream()) { input.transferTo(log); }
                catch (IOException ignored) { }
            });
            if (!process.waitFor(properties.compileTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
                reader.join(5_000);
                throw new PermanentTaskException("MOCK_EXAM_PDF_FAILED", "XeLaTeX 编译超时");
            }
            reader.join(5_000);
            if (process.exitValue() != 0) {
                XeLatexMockExamArtifactGenerator.log.warn("XeLaTeX compile failed tex={} output={}",
                        tex.getFileName(), safe(log.text()));
                throw new PermanentTaskException("MOCK_EXAM_PDF_FAILED",
                        "XeLaTeX 编译失败，请检查试题公式或模板依赖");
            }
        }
        return directory.resolve(tex.getFileName().toString().replaceFirst("\\.tex$", ".pdf"));
    }
    private void validateLocal(Path pdf) throws Exception {
        if (!Files.isRegularFile(pdf)) throw new PermanentTaskException("MOCK_EXAM_PDF_FAILED", "PDF 未生成");
        long size = Files.size(pdf); if (size <= 0 || size > properties.maxFileSize().toBytes())
            throw new PermanentTaskException("MOCK_EXAM_PDF_FAILED", "PDF 大小不合法");
        try (var document = Loader.loadPDF(pdf.toFile())) { if (document.getNumberOfPages() < 1)
            throw new PermanentTaskException("MOCK_EXAM_PDF_FAILED", "PDF 没有有效页面"); }
    }
    private void validateRemote(String key) throws Exception {
        try (var input = storage.get(key)) {
            var bytes = input.readNBytes(Math.toIntExact(properties.maxFileSize().toBytes() + 1));
            if (bytes.length == 0 || bytes.length > properties.maxFileSize().toBytes())
                throw new StorageOperationException("远端 PDF 大小不合法", null);
            try (var document = Loader.loadPDF(bytes)) { if (document.getNumberOfPages() < 1)
                throw new StorageOperationException("远端 PDF 没有有效页面", null); }
        }
    }
    boolean cleanupObjects(boolean paper, boolean answer, String paperKey, String answerKey) {
        boolean cleaned = true;
        if (paper) try { storage.delete(paperKey); } catch (RuntimeException ignored) { cleaned = false; }
        if (answer) try { storage.delete(answerKey); } catch (RuntimeException ignored) { cleaned = false; }
        return cleaned;
    }
    private void deleteTree(Path directory, Path root) {
        if (!directory.toAbsolutePath().normalize().startsWith(root) || directory.equals(root)) return;
        try (var paths = Files.walk(directory)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> {
            try { Files.deleteIfExists(path); } catch (IOException ignored) { }
        }); } catch (IOException ignored) { }
    }
    private String safe(String value) { if (value == null || value.isBlank()) return "模拟卷 PDF 生成失败";
        var cleaned = value.replaceAll("[\\r\\n]+", " "); return cleaned.substring(0, Math.min(500, cleaned.length())); }
    private static final class BoundedLog extends OutputStream {
        private final byte[] values; private int position; private long count;
        private BoundedLog(int capacity) { values = new byte[capacity]; }
        @Override public void write(int value) { values[position] = (byte) value;
            position = (position + 1) % values.length; count++; }
        String text() {
            int length = (int) Math.min(count, values.length); var ordered = new byte[length];
            int start = count < values.length ? 0 : position;
            for (int i = 0; i < length; i++) ordered[i] = values[(start + i) % values.length];
            return new String(ordered, StandardCharsets.UTF_8);
        }
    }
}
