package com.finalweek.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.finalweek.task.ParseTask;
import com.finalweek.task.PermanentTaskException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class TextMaterialParserTest {
    private final TextMaterialParser parser = new TextMaterialParser();
    @TempDir Path directory;

    @Test void extractsParagraphsWithStableNumbers() throws Exception {
        var source = directory.resolve("notes.md");
        Files.writeString(source, "# 第一章\n概念 A\n\n概念 B\r\n\r\n结论", StandardCharsets.UTF_8);
        var result = parser.extract(mock(ParseTask.class), mock(Material.class), source, directory);
        assertThat(result.units()).extracting(ExtractedUnit::paragraphNumber).containsExactly(1, 2, 3);
        assertThat(result.units()).extracting(ExtractedUnit::content)
                .containsExactly("# 第一章\n概念 A", "概念 B", "结论");
    }

    @Test void rejectsBinaryContent() throws Exception {
        var source = directory.resolve("fake.txt"); Files.write(source, new byte[]{'a', 0, 'b'});
        assertThatThrownBy(() -> parser.extract(mock(ParseTask.class), mock(Material.class), source, directory))
                .isInstanceOfSatisfying(PermanentTaskException.class,
                        exception -> assertThat(exception.code()).isEqualTo("TEXT_ENCODING_INVALID"));
    }
}
