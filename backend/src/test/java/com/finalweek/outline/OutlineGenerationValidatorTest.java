package com.finalweek.outline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutlineGenerationValidatorTest {
    private final OutlineGenerationValidator validator = new OutlineGenerationValidator(new ObjectMapper());

    @Test
    void validatesTreeImportanceAndContextBoundSources() {
        var source = UUID.randomUUID();
        var json = """
                {"nodes":[{"title":"Newton laws","importance":"HIGH","sourceSegmentIds":["%s"],
                "children":[{"title":"Second law","importance":"MEDIUM","sourceSegmentIds":["%s"],"children":[]}]}]}
                """.formatted(source, source);

        var result = validator.parseAndValidate(json, Set.of(source));

        assertThat(result.nodes()).hasSize(1);
        assertThat(result.nodes().getFirst().children()).hasSize(1);
    }

    @Test
    void rejectsUnretrievedOrMissingSources() {
        var foreign = UUID.randomUUID();
        var json = """
                {"nodes":[{"title":"Invented","importance":"LOW","sourceSegmentIds":["%s"],"children":[]}]}
                """.formatted(foreign);

        assertThatThrownBy(() -> validator.parseAndValidate(json, Set.of(UUID.randomUUID())))
                .isInstanceOf(OutlineGenerationValidator.InvalidOutlineException.class)
                .hasMessageContaining("本次检索上下文");
    }
}
