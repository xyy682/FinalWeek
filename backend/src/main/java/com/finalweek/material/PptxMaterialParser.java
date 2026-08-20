package com.finalweek.material;

import com.finalweek.ai.OcrClient;
import com.finalweek.task.BackgroundTask;
import com.finalweek.task.PermanentTaskException;
import com.finalweek.task.RetryableTaskException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

@Component
public class PptxMaterialParser implements MaterialParser {
    private static final int NATIVE_TEXT_THRESHOLD = 12;
    private final OcrClient ocr;
    public PptxMaterialParser(OcrClient ocr) { this.ocr = ocr; }
    @Override public boolean supports(String mediaType) {
        return mediaType.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }
    @Override public ExtractionResult extract(BackgroundTask task, Material material, Path source, Path workDirectory) {
        var nativeTexts = new ArrayList<String>();
        try (var input = Files.newInputStream(source); var deck = new XMLSlideShow(input)) {
            deck.getSlides().forEach(slide -> nativeTexts.add(slide.getShapes().stream()
                    .filter(XSLFTextShape.class::isInstance).map(XSLFTextShape.class::cast)
                    .map(XSLFTextShape::getText).filter(value -> value != null && !value.isBlank())
                    .reduce("", (left, right) -> left.isBlank() ? right : left + "\n" + right).strip()));
        } catch (Exception exception) { throw new PermanentTaskException("PPTX_PARSE_FAILED", "PPTX 文件损坏或无法读取"); }

        ExternalProcess.run(workDirectory, Duration.ofMinutes(2), "PPTX_RENDER_FAILED",
                List.of("soffice", "-env:UserInstallation=" + workDirectory.resolve("lo-profile").toUri(),
                        "--headless", "--convert-to", "pdf", "--outdir",
                        workDirectory.toString(), source.toString()));
        var preview = workDirectory.resolve("source.pdf");
        if (!Files.isRegularFile(preview)) throw new PermanentTaskException("PPTX_RENDER_FAILED", "PPTX 未生成预览 PDF");
        var units = new ArrayList<ExtractedUnit>(); var warnings = new ArrayList<String>();
        try (var pdf = Loader.loadPDF(preview.toFile())) {
            var renderer = new PDFRenderer(pdf);
            for (int index = 0; index < nativeTexts.size(); index++) {
                var text = nativeTexts.get(index);
                if (text.replaceAll("\\s", "").length() < NATIVE_TEXT_THRESHOLD && index < pdf.getNumberOfPages()) {
                    var image = workDirectory.resolve("ppt-slide-" + (index + 1) + ".png");
                    ImageIO.write(renderer.renderImageWithDPI(index, 160), "png", image.toFile());
                    try { text = ocr.recognize(task.getId(), image).strip(); }
                    catch (RuntimeException exception) {
                        if (!text.isBlank()) warnings.add("第 " + (index + 1) + " 张幻灯片 OCR 失败，已保留原生文字");
                        else throw exception;
                    }
                }
                if (!text.isBlank()) units.add(ExtractedUnit.slide(index + 1, text));
                else warnings.add("第 " + (index + 1) + " 张幻灯片未提取到文字");
            }
        } catch (PermanentTaskException | RetryableTaskException exception) { throw exception; }
        catch (Exception exception) { throw new PermanentTaskException("PPTX_RENDER_FAILED", "PPTX 预览页面读取失败"); }
        if (units.isEmpty()) throw new PermanentTaskException("CONTENT_EMPTY", "PPTX 没有可提取文字");
        return new ExtractionResult(units, warnings, preview, null);
    }
}
