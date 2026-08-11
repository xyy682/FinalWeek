package com.finalweek.outline;

import java.util.List;
import java.util.UUID;

public record GeneratedOutline(List<Node> nodes) {
    public GeneratedOutline { nodes = nodes == null ? List.of() : List.copyOf(nodes); }
    public record Node(String title, OutlineImportance importance, List<UUID> sourceSegmentIds, List<Node> children) {
        public Node {
            sourceSegmentIds = sourceSegmentIds == null ? List.of() : List.copyOf(sourceSegmentIds);
            children = children == null ? List.of() : List.copyOf(children);
        }
    }
}
