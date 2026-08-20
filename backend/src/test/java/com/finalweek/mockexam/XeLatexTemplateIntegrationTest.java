package com.finalweek.mockexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "XELATEX_EXECUTABLE", matches = ".+")
class XeLatexTemplateIntegrationTest {
    @TempDir Path directory;

    @Test void rendersChineseMixedTextAndCommonFormulaIntoBothValidPdfs() throws Exception {
        var exam = mock(MockExam.class);
        when(exam.getPdfTitle()).thenReturn("大学物理模拟卷");
        when(exam.getTotalScoreRequested()).thenReturn(20);
        when(exam.getDurationMinutes()).thenReturn(30);
        var source = java.util.UUID.randomUUID();
        var generated = new GeneratedMockExam(List.of(
                new GeneratedMockExam.Question(MockExamQuestionType.SINGLE_CHOICE,
                        "若 F=ma，质量不变时合力增大，加速度如何变化？",
                        List.of("增大", "减小", "不变", "无法判断"),
                        new GeneratedMockExam.Answer(List.of(0), null, null, null, null, null),
                        5, false, List.of(source), List.of()),
                new GeneratedMockExam.Question(MockExamQuestionType.FILL_BLANK,
                        "写出位移公式中的加速度项。", List.of(),
                        new GeneratedMockExam.Answer(null, null, List.of("二分之一乘加速度与时间平方"),
                                null, null, null),
                        5, false, List.of(source),
                        List.of(new GeneratedMockExam.Formula("s=v_0t+\\frac{1}{2}at^2", "stem"))),
                new GeneratedMockExam.Question(MockExamQuestionType.CALCULATION,
                        "A 2 kg object has acceleration 3 m/s². Calculate the force.", List.of(),
                        new GeneratedMockExam.Answer(null, null, null, null,
                                List.of("代入牛顿第二定律", "计算质量与加速度的乘积"), "6 N"),
                        10, true, List.of(),
                        List.of(new GeneratedMockExam.Formula("F=ma=2\\times3=6\\,\\mathrm{N}", "answer")))
        ));
        var renderer = new MockExamTexRenderer(new TexEscaper(), new MockExamFormulaValidator());
        var documents = renderer.render(exam, generated);

        var paper = compile("paper", documents.paperTex());
        var answer = compile("answer", documents.answerTex());

        assertThat(documents.paperTex()).doesNotContain("使用通用知识补充", "@@");
        assertThat(documents.paperTex()).doesNotContain("F=ma=2\\times3=6");
        assertThat(documents.answerTex()).contains("使用通用知识补充").doesNotContain("@@");
        assertThat(documents.answerTex()).contains("F=ma=2\\times3=6");
        assertValidPdf(paper);
        assertValidPdf(answer);
        exportForVisualReview(paper, answer);
    }

    private Path compile(String name, String tex) throws Exception {
        var source = directory.resolve(name + ".tex");
        Files.writeString(source, tex, StandardCharsets.UTF_8);
        for (int pass = 0; pass < 2; pass++) {
            var process = new ProcessBuilder(System.getenv("XELATEX_EXECUTABLE"), "-no-shell-escape",
                    "-halt-on-error", "-interaction=nonstopmode", source.getFileName().toString())
                    .directory(directory.toFile()).redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as(output).isZero();
        }
        return directory.resolve(name + ".pdf");
    }

    private void assertValidPdf(Path pdf) throws Exception {
        assertThat(pdf).isRegularFile();
        assertThat(Files.size(pdf)).isGreaterThan(1_000);
        try (var document = Loader.loadPDF(pdf.toFile())) {
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        }
    }

    private void exportForVisualReview(Path paper, Path answer) throws Exception {
        var configured = System.getenv("MOCK_EXAM_PDF_TEST_OUTPUT_DIR");
        if (configured == null || configured.isBlank()) return;
        var output = Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(output);
        exportDocument(paper, output.resolve("mock-exam-paper.pdf"), output.resolve("mock-exam-paper-1.png"));
        exportDocument(answer, output.resolve("mock-exam-answer.pdf"), output.resolve("mock-exam-answer-1.png"));
    }

    private void exportDocument(Path source, Path pdf, Path firstPagePng) throws Exception {
        Files.copy(source, pdf, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(source.resolveSibling(source.getFileName().toString().replaceFirst("\\.pdf$", ".tex")),
                pdf.resolveSibling(pdf.getFileName().toString().replaceFirst("\\.pdf$", ".tex")),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var document = Loader.loadPDF(source.toFile())) {
            var image = new PDFRenderer(document).renderImageWithDPI(0, 144, ImageType.RGB);
            assertThat(ImageIO.write(image, "png", firstPagePng.toFile())).isTrue();
        }
    }
}
