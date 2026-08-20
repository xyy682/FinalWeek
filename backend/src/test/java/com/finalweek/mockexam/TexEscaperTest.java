package com.finalweek.mockexam;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class TexEscaperTest {
    @Test void escapesEveryTexControlCharacterAndDropsControls() {
        var value = new TexEscaper().escape("\\ { } # $ % & _ ^ ~\n正文\u0000");
        assertThat(value).contains("\\textbackslash{}", "\\{", "\\}", "\\#", "\\$", "\\%",
                "\\&", "\\_", "\\textasciicircum{}", "\\textasciitilde{}", "\\par", "正文")
                .doesNotContain("\u0000");
    }
}
