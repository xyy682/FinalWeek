package com.finalweek.knowledge;

import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.material.CourseContext;
import com.finalweek.material.ExtractedUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SemanticChunker {
    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHan}]|[\\p{L}\\p{N}_]+|[^\\s]");
    private static final Pattern SEMANTIC_BOUNDARY = Pattern.compile(
            "(?<=[。！？!?])|(?<=[.])(?=\\s)|\\R+");
    private final int maximum;
    private final int overlap;

    @Autowired
    public SemanticChunker(FinalWeekProperties properties) {
        this(properties.retrieval().chunkMaxTokens(), properties.retrieval().chunkOverlapTokens());
    }

    SemanticChunker(int maximum, int overlap) {
        this.maximum = maximum;
        this.overlap = overlap;
        if (maximum < 32 || overlap < 0 || overlap >= maximum) {
            throw new IllegalArgumentException("CHUNK_MAX_TOKENS 必须至少为 32，overlap 必须小于上限");
        }
    }

    public List<SegmentChunk> chunk(CourseContext context) {
        var result = new ArrayList<SegmentChunk>();
        for (var unit : context.units()) splitUnit(unit, result);
        return List.copyOf(result);
    }

    private void splitUnit(ExtractedUnit source, List<SegmentChunk> output) {
        var text = source.content() == null ? "" : source.content().strip();
        if (text.isBlank()) return;
        var parts = SEMANTIC_BOUNDARY.split(text);
        var current = new StringBuilder();
        for (var raw : parts) {
            var part = raw.strip();
            if (part.isBlank()) continue;
            if (count(part) > maximum) {
                flush(source, current, output);
                window(source, part, output);
                continue;
            }
            var candidate = current.isEmpty() ? part : current + "\n" + part;
            if (count(candidate) <= maximum) {
                if (!current.isEmpty()) current.append('\n');
                current.append(part);
            } else {
                var previous = current.toString();
                flush(source, current, output);
                var tail = tail(previous, overlap);
                if (!tail.isBlank() && count(tail + "\n" + part) <= maximum) current.append(tail).append('\n');
                current.append(part);
            }
        }
        flush(source, current, output);
    }

    private void window(ExtractedUnit source, String text, List<SegmentChunk> output) {
        var tokens = tokens(text);
        int start = 0;
        while (start < tokens.size()) {
            int end = Math.min(start + maximum, tokens.size());
            int startOffset = tokens.get(start).start();
            int endOffset = tokens.get(end - 1).end();
            add(source, text.substring(startOffset, endOffset).strip(), end - start, output);
            if (end == tokens.size()) break;
            start = end - overlap;
        }
    }

    private void flush(ExtractedUnit source, StringBuilder current, List<SegmentChunk> output) {
        if (current.isEmpty()) return;
        var value = current.toString().strip();
        add(source, value, count(value), output);
        current.setLength(0);
    }

    private void add(ExtractedUnit source, String content, int tokenCount, List<SegmentChunk> output) {
        output.add(new SegmentChunk(new ExtractedUnit(source.sourceType(), content, source.pageNumber(),
                source.slideNumber(), source.paragraphNumber(), source.startTimeMs(), source.endTimeMs(),
                source.asrText(), source.ocrText()), tokenCount));
    }

    int count(String text) { return tokens(text).size(); }

    private String tail(String text, int requested) {
        if (requested == 0) return "";
        var tokens = tokens(text);
        if (tokens.size() <= requested) return text;
        return text.substring(tokens.get(tokens.size() - requested).start()).strip();
    }

    private List<TokenSpan> tokens(String text) {
        var result = new ArrayList<TokenSpan>();
        var matcher = TOKEN.matcher(text);
        while (matcher.find()) result.add(new TokenSpan(matcher.start(), matcher.end()));
        return result;
    }

    private record TokenSpan(int start, int end) {}
}
