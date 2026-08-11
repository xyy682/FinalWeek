package com.finalweek.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finalweek.material.CourseContext;
import com.finalweek.material.CourseSegment;
import com.finalweek.material.ExtractedUnit;
import com.finalweek.material.Material;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SemanticChunkerTest {
    @Test
    void keepsSourceLocationAndUsesFixedOverlapForOversizedSemanticUnit() {
        var chunker = new SemanticChunker(32, 4);
        var text = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen "
                + "sixteen seventeen eighteen nineteen twenty twentyone twentytwo twentythree twentyfour "
                + "twentyfive twentysix twentyseven twentyeight twentynine thirty thirtyone thirtytwo "
                + "thirtythree thirtyfour thirtyfive";
        var context = new CourseContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null,
                List.of(), List.of(ExtractedUnit.paragraph(7, text)));

        var chunks = chunker.chunk(context);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(32);
            assertThat(chunk.unit().paragraphNumber()).isEqualTo(7);
        });
        assertThat(chunks.get(1).unit().content()).startsWith("twentynine thirty thirtyone thirtytwo");
    }

    @Test
    void segmentIdentityIsStableForMaterialAndChunkNumber() {
        var materialId = UUID.randomUUID();
        var material = mock(Material.class);
        when(material.getId()).thenReturn(materialId);
        var userId = UUID.randomUUID();
        var courseId = UUID.randomUUID();
        var unit = ExtractedUnit.page(3, "Newton second law");

        var first = new CourseSegment(userId, courseId, material, 5, 3, unit);
        var repeated = new CourseSegment(userId, courseId, material, 5, 3, unit);

        assertThat(first.getId()).isEqualTo(repeated.getId())
                .isEqualTo(CourseSegment.stableId(materialId, 5));
    }
}
