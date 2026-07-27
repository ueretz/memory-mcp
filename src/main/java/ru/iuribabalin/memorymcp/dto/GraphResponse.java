package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.MemoryNode;

import java.util.List;

public record GraphResponse(List<GraphNode> nodes, List<GraphEdge> edges) {

    public record GraphNode(String name, MemoryNode.Type type) {
    }

    public record GraphEdge(String source, String target) {
    }
}
