package com.finalweek.outline;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OutlineGenerationValidator {
    private final ObjectMapper mapper;
    public OutlineGenerationValidator(ObjectMapper mapper) { this.mapper = mapper; }

    public GeneratedOutline parseAndValidate(String json, Set<UUID> allowedSources) {
        try {
            var value = mapper.readValue(json, GeneratedOutline.class);
            if (value.nodes().isEmpty()) throw invalid("nodes 至少包含一个根节点");
            var count = new Counter();
            validate(value.nodes(), allowedSources, 1, count);
            return value;
        } catch (InvalidOutlineException exception) { throw exception; }
        catch (Exception exception) { throw invalid("返回内容不是符合 DTO 的 JSON"); }
    }

    private void validate(java.util.List<GeneratedOutline.Node> nodes, Set<UUID> allowed, int depth, Counter count) {
        if (nodes.isEmpty()) return;
        if (depth > 4) throw invalid("提纲层级不能超过 4 层");
        for (var node : nodes) {
            count.value++;
            if (count.value > 100) throw invalid("知识点不能超过 100 个");
            if (node.title() == null || node.title().isBlank() || node.title().length() > 200) {
                throw invalid("知识点标题长度必须为 1–200");
            }
            if (node.importance() == null) throw invalid("知识点重要度必须为 HIGH/MEDIUM/LOW");
            if (node.sourceSegmentIds().isEmpty()) throw invalid("每个知识点至少需要一个来源");
            var unique = new HashSet<>(node.sourceSegmentIds());
            if (unique.size() != node.sourceSegmentIds().size() || !allowed.containsAll(unique)) {
                throw invalid("知识点来源必须唯一且属于本次检索上下文");
            }
            validate(node.children(), allowed, depth + 1, count);
        }
    }
    private InvalidOutlineException invalid(String message) { return new InvalidOutlineException(message); }
    static final class Counter { int value; }
    public static final class InvalidOutlineException extends RuntimeException {
        public InvalidOutlineException(String message) { super(message); }
    }
}
