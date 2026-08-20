package com.finalweek.mockexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.finalweek.upload.ObjectStorage;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class XeLatexMockExamArtifactGeneratorTest {
    @Test void partialObjectCleanupMustBeConfirmedBeforeSameExamCanRetry() {
        var storage = mock(ObjectStorage.class);
        doThrow(new IllegalStateException("minio unavailable")).when(storage).delete("answer");
        var generator = new XeLatexMockExamArtifactGenerator(mock(MockExamTexRenderer.class),
                new MockExamPdfProperties("xelatex", Duration.ofSeconds(10), DataSize.ofMegabytes(20), DataSize.ofKilobytes(512),
                        Path.of("tmp"), "v1", "v1"), storage, mock(MockExamCleanupService.class));

        assertThat(generator.cleanupObjects(true, true, "paper", "answer")).isFalse();
        verify(storage).delete("paper");
        verify(storage).delete("answer");
    }

    @Test void absentObjectsAreNotDeletedAndSuccessfulCleanupIsConfirmed() {
        var storage = mock(ObjectStorage.class);
        var generator = new XeLatexMockExamArtifactGenerator(mock(MockExamTexRenderer.class),
                new MockExamPdfProperties("xelatex", Duration.ofSeconds(10), DataSize.ofMegabytes(20), DataSize.ofKilobytes(512),
                        Path.of("tmp"), "v1", "v1"), storage, mock(MockExamCleanupService.class));

        assertThat(generator.cleanupObjects(true, false, "paper", "answer")).isTrue();
        verify(storage).delete("paper");
        verify(storage, never()).delete("answer");
    }
}
