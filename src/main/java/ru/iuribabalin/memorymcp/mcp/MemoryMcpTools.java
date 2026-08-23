package ru.iuribabalin.memorymcp.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.iuribabalin.memorymcp.dto.GraphResponse;
import ru.iuribabalin.memorymcp.dto.MemoryEntryDetail;
import ru.iuribabalin.memorymcp.dto.MemoryEntrySummary;
import ru.iuribabalin.memorymcp.dto.SaveMemoryRequest;
import ru.iuribabalin.memorymcp.entity.MemoryNode;
import ru.iuribabalin.memorymcp.service.MemoryService;

import java.util.List;
import java.util.Map;

@Component
public class MemoryMcpTools {

    private final MemoryService memoryService;

    public MemoryMcpTools(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @McpTool(name = "memory_save",
            description = "Create or update a long-term memory entry. Upserts by name. Parses [[other-name]] " +
                    "references inside content into graph links. Call this whenever you learn a durable user " +
                    "preference, receive corrective feedback, discover a project-specific fact, or produce " +
                    "reusable reference knowledge. If you're mid-task, pass projectScope/taskKey - omitting them " +
                    "while linking to a task-scoped entry makes this one unreachable from that task's page and " +
                    "graph, and the response's warnings field will flag it.")
    public MemoryEntryDetail memorySave(
            @McpToolParam(description = "Unique kebab-case slug identifying this entry (use the fully-qualified class name for type=LOCATION)", required = true) String name,
            @McpToolParam(description = "One of USER, FEEDBACK, PROJECT, REFERENCE, LOCATION, REPORT", required = true) MemoryNode.Type type,
            @McpToolParam(description = "One-line summary shown in cheap listings", required = true) String description,
            @McpToolParam(description = "Full markdown content; may reference other entries via [[name]]. For type=REPORT, a full " +
                    "self-contained HTML document instead (inline CSS/JS, no external CDN/resources) - it's rendered " +
                    "as a real HTML page in the dashboard, not markdown-parsed", required = true) String content,
            @McpToolParam(description = "Project this entry is scoped to, auto-derived from the git repo name", required = false) String projectScope,
            @McpToolParam(description = "Task key to scope this entry to a specific task (must already exist via task_start); omit for project-level common context", required = false) String taskKey,
            @McpToolParam(description = "Relative file path this entry points at, for type=LOCATION (e.g. a class or file you just worked on)", required = false) String filePath,
            @McpToolParam(description = "Who created this entry, e.g. 'Name <email>' - auto-derive from `git config user.name`/`user.email` in the current repo, never ask the user for it", required = false) String createdBy) {
        return memoryService.save(new SaveMemoryRequest(name, type, description, content, projectScope, taskKey, filePath, createdBy));
    }

    @McpTool(name = "memory_get",
            description = "Fetch the full content of one memory entry by name, including entries it links to and " +
                    "entries that link to it.")
    public MemoryEntryDetail memoryGet(
            @McpToolParam(description = "The entry's name/slug", required = true) String name) {
        return memoryService.get(name);
    }

    @McpTool(name = "memory_list",
            description = "Cheap index of memory entries (name, type, description, updatedAt only - no content). " +
                    "Call this first when you need an overview, instead of memory_get on everything.")
    public List<MemoryEntrySummary> memoryList(
            @McpToolParam(description = "Optional filter: USER, FEEDBACK, PROJECT, or REFERENCE", required = false) MemoryNode.Type type,
            @McpToolParam(description = "Project scope filter. Alone (no taskKey), returns only project-level common entries", required = false) String projectScope,
            @McpToolParam(description = "Task key filter - lists that task's entries instead of the project's common ones", required = false) String taskKey,
            @McpToolParam(description = "Max results, default 50", required = false) Integer limit,
            @McpToolParam(description = "Offset for pagination, default 0", required = false) Integer offset) {
        return memoryService.list(type, projectScope, taskKey, limit == null ? 50 : limit, offset == null ? 0 : offset);
    }

    @McpTool(name = "memory_search",
            description = "Full-text search over entry names, descriptions, and content. Returns the same cheap " +
                    "summary shape as memory_list, ranked by relevance.")
    public List<MemoryEntrySummary> memorySearch(
            @McpToolParam(description = "Search query", required = true) String query,
            @McpToolParam(description = "Optional filter: USER, FEEDBACK, PROJECT, or REFERENCE", required = false) MemoryNode.Type type,
            @McpToolParam(description = "Optional project scope filter", required = false) String projectScope,
            @McpToolParam(description = "Optional task key filter (requires projectScope)", required = false) String taskKey,
            @McpToolParam(description = "Max results, default 20", required = false) Integer limit) {
        return memoryService.search(query, type, projectScope, taskKey, limit == null ? 20 : limit);
    }

    @McpTool(name = "memory_graph",
            description = "Return the full memory graph as nodes and edges (derived from [[links]] in content), " +
                    "for visualization or for understanding how entries relate.")
    public GraphResponse memoryGraph(
            @McpToolParam(description = "Optional filter: USER, FEEDBACK, PROJECT, or REFERENCE", required = false) MemoryNode.Type type,
            @McpToolParam(description = "Project scope filter. Alone (no taskKey), returns only project-level common entries", required = false) String projectScope,
            @McpToolParam(description = "Task key filter - graph of that task's entries instead of the project's common ones", required = false) String taskKey) {
        return memoryService.graph(type, projectScope, taskKey);
    }

    @McpTool(name = "memory_related",
            description = "Return entries directly linked to/from the given entry - cheaper than memory_get when " +
                    "you just need to know what's connected before re-deriving something already documented.")
    public List<MemoryEntrySummary> memoryRelated(
            @McpToolParam(description = "The entry's name/slug", required = true) String name,
            @McpToolParam(description = "Traversal depth, default 1 (only 1-hop is currently supported)", required = false) Integer depth) {
        return memoryService.related(name, depth == null ? 1 : depth);
    }

    @McpTool(name = "memory_delete",
            description = "Delete a memory entry and its links. Use when a memory has become stale or wrong.")
    public Map<String, Object> memoryDelete(
            @McpToolParam(description = "The entry's name/slug", required = true) String name) {
        boolean deleted = memoryService.delete(name);
        return Map.of("deleted", deleted, "name", name);
    }
}
