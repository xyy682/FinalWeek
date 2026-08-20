package com.finalweek.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.finalweek.ai.OcrClient;
import com.finalweek.task.BackgroundTask;
import com.finalweek.task.PermanentTaskException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfMaterialParserTest {
    @TempDir Path directory;

    @Test void keepsPageNumberForNativeText() throws Exception {
        var source = createPdf("This is native course material text on page one.");
        var ocr = mock(OcrClient.class); var task = task();
        var result = new PdfMaterialParser(ocr).extract(task, mock(Material.class), source, directory);
        assertThat(result.units()).hasSize(1);
        assertThat(result.units().getFirst().pageNumber()).isEqualTo(1);
        assertThat(result.units().getFirst().content()).contains("native course material");
        verifyNoInteractions(ocr);
    }

    @Test void rendersLowDensityPageAndUsesOcr() throws Exception {
        var source = createPdf(""); var ocr = mock(OcrClient.class); var task = task();
        when(ocr.recognize(any(), any())).thenReturn("扫描页识别文字");
        var result = new PdfMaterialParser(ocr).extract(task, mock(Material.class), source, directory);
        assertThat(result.units().getFirst().content()).isEqualTo("扫描页识别文字");
        verify(ocr).recognize(eq(task.getId()), any(Path.class));
    }

    @Test void rejectsDamagedPdf() throws Exception {
        var source = directory.resolve("damaged.pdf"); Files.writeString(source, "%PDF damaged");
        assertThatThrownBy(() -> new PdfMaterialParser(mock(OcrClient.class))
                .extract(task(), mock(Material.class), source, directory))
                .isInstanceOfSatisfying(PermanentTaskException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PDF_PARSE_FAILED"));
    }

    private BackgroundTask task() { var task = mock(BackgroundTask.class); when(task.getId()).thenReturn(UUID.randomUUID()); return task; }
    private Path createPdf(String text) throws Exception {
        var source = directory.resolve(UUID.randomUUID() + ".pdf");
        try (var document = new PDDocument()) {
            var page = new PDPage(); document.addPage(page);
            if (!text.isBlank()) try (var content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700); content.showText(text); content.endText();
            }
            document.save(source.toFile());
        }
        return source;
    }
}
