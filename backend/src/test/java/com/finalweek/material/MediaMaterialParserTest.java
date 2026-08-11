package com.finalweek.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MediaMaterialParserTest {
    @Test void mergesSpeechAndNearbyFrameWhileKeepingBothSources() {
        var speech = timed("讲解牛顿第二定律", 1_000, 4_000, "讲解牛顿第二定律", null);
        var frame = timed("F = ma", 2_000, 3_000, null, "F = ma");

        var merged = MediaMaterialParser.merge(List.of(speech), List.of(frame), true);

        assertThat(merged).singleElement().satisfies(unit -> {
            assertThat(unit.content()).contains("讲解牛顿第二定律", "F = ma");
            assertThat(unit.asrText()).isEqualTo("讲解牛顿第二定律");
            assertThat(unit.ocrText()).isEqualTo("F = ma");
            assertThat(unit.startTimeMs()).isEqualTo(1_000);
        });
    }

    @Test void preservesEitherRouteWhenTheOtherRouteIsEmpty() {
        var speech = timed("仅音轨", 5_000, 6_000, "仅音轨", null);
        var frame = timed("仅画面", 10_000, 11_000, null, "仅画面");

        assertThat(MediaMaterialParser.merge(List.of(speech), List.of(), true))
                .extracting(ExtractedUnit::content).containsExactly("仅音轨");
        assertThat(MediaMaterialParser.merge(List.of(), List.of(frame), true))
                .extracting(ExtractedUnit::content).containsExactly("仅画面");
    }

    private ExtractedUnit timed(String content, long start, long end, String asr, String ocr) {
        return new ExtractedUnit(SourceType.VIDEO_TIME, content, null, null, null, start, end, asr, ocr);
    }
}
