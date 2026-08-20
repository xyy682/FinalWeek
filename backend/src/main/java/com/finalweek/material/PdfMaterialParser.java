package com.finalweek.material;

import com.finalweek.ai.OcrClient;
import com.finalweek.task.BackgroundTask;
import com.finalweek.task.PermanentTaskException;
import com.finalweek.task.RetryableTaskException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class PdfMaterialParser implements MaterialParser {
    private static final int NATIVE_TEXT_THRESHOLD = 20;
    private final OcrClient ocr;
    public PdfMaterialParser(OcrClient ocr) { this.ocr = ocr; }
    @Override public boolean supports(String mediaType) { return mediaType.equals("application/pdf"); }
    @Override public ExtractionResult extract(BackgroundTask task, Material material, Path source, Path workDirectory) {
        var units = new ArrayList<ExtractedUnit>(); var warnings = new ArrayList<String>();
        try (var document = Loader.loadPDF(source.toFile())) {
            if (document.isEncrypted()) throw new PermanentTaskException("PDF_ENCRYPTED", "暂不支持加密 PDF");
            var stripper = new PDFTextStripper(); var renderer = new PDFRenderer(document);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                var text = stripper.getText(document).strip();
                if (text.replaceAll("\\s", "").length() < NATIVE_TEXT_THRESHOLD) {
                    var imageFile = workDirectory.resolve("pdf-page-" + page + ".png");
                    ImageIO.write(renderer.renderImageWithDPI(page - 1, 160), "png", imageFile.toFile());
                    try { text = ocr.recognize(task.getId(), imageFile).strip(); }
                    catch (RuntimeException exception) {
                        if (!text.isBlank()) warnings.add("第 " + page + " 页 OCR 失败，已保留原生文字");
                        else throw exception;
                    }
                }
                if (!text.isBlank()) units.add(ExtractedUnit.page(page, text));
                else warnings.add("第 " + page + " 页未提取到文字");
            }
            if (units.isEmpty()) throw new PermanentTaskException("CONTENT_EMPTY", "PDF 没有可提取文字");
            return new ExtractionResult(units, warnings, source, null);
        } catch (PermanentTaskException | RetryableTaskException exception) { throw exception; }
        catch (Exception exception) { throw new PermanentTaskException("PDF_PARSE_FAILED", "PDF 文件损坏或无法解析"); }
    }
}
