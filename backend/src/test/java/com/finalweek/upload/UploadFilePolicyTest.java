package com.finalweek.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadFilePolicyTest {
    @TempDir Path temporaryDirectory;
    private final UploadFilePolicy policy = new UploadFilePolicy(properties());

    @Test
    void acceptsPdfSignatureAndNormalizesExtension() throws Exception {
        var file = temporaryDirectory.resolve("sample.pdf");
        Files.writeString(file, "%PDF-1.7\nbody", StandardCharsets.US_ASCII);
        var declaration = policy.validateDeclaration("Lecture.PDF", Files.size(file));
        policy.validateContent(file, declaration.extension());
        assertThat(declaration.mediaType()).isEqualTo("application/pdf");
    }

    @Test
    void rejectsForgedPdfAndUnsupportedExtension() throws Exception {
        var file = temporaryDirectory.resolve("fake.pdf");
        Files.writeString(file, "not a pdf", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> policy.validateContent(file, "pdf")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validateDeclaration("notes.docx", 10))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FILE_TYPE_UNSUPPORTED"));
    }

    @Test
    void acceptsUtf8TextMp3Mp4AndPptxSignatures() throws Exception {
        var text = temporaryDirectory.resolve("notes.md");
        Files.writeString(text, "# 复习笔记\n内容", StandardCharsets.UTF_8);
        policy.validateContent(text, "md");

        var mp3 = temporaryDirectory.resolve("lecture.mp3");
        Files.write(mp3, new byte[] {'I', 'D', '3', 4, 0, 0});
        policy.validateContent(mp3, "mp3");

        var mp4 = temporaryDirectory.resolve("lecture.mp4");
        Files.write(mp4, new byte[] {0, 0, 0, 12, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'});
        policy.validateContent(mp4, "mp4");

        var pptx = temporaryDirectory.resolve("slides.pptx");
        try (var output = new ZipOutputStream(Files.newOutputStream(pptx))) {
            output.putNextEntry(new ZipEntry("[Content_Types].xml")); output.write("types".getBytes()); output.closeEntry();
            output.putNextEntry(new ZipEntry("ppt/presentation.xml")); output.write("ppt".getBytes()); output.closeEntry();
        }
        policy.validateContent(pptx, "pptx");
    }

    private static FinalWeekProperties properties() {
        return new FinalWeekProperties(
                new FinalWeekProperties.Auth(Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofMinutes(10), 5, 5),
                new FinalWeekProperties.Limits(8, Duration.ofHours(24), 100, 2048, Duration.ofHours(2), 5, 30, 5, 20),
                new FinalWeekProperties.Retrieval(20, 20, 8, 60),
                new FinalWeekProperties.Ai(Duration.ofSeconds(60), Duration.ofSeconds(60), 20, "asr", "ocr", "embedding", "llm"));
    }
}
