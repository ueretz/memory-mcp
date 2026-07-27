package ru.iuribabalin.memorymcp.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.iuribabalin.memorymcp.service.RepositoryScanner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;

@Component
public class CodeMapMcpTools {

    private final RepositoryScanner repositoryScanner;

    public CodeMapMcpTools(RepositoryScanner repositoryScanner) {
        this.repositoryScanner = repositoryScanner;
    }

    @McpTool(name = "location_scan",
            description = "Walk a project's source tree and index every file as a LOCATION memory entry, so " +
                    "'where is X' can be answered via memory_search/memory_list (type=LOCATION) instead of " +
                    "grepping the filesystem. For Java files, extracts the class name and in-project imports so " +
                    "memory_graph shows a real class dependency graph. Run once per project (or after a big " +
                    "restructuring); safe to re-run, it upserts by name.")
    public Map<String, Object> locationScan(
            @McpToolParam(description = "Project identifier, auto-derived from the git repo name", required = true) String projectScope,
            @McpToolParam(description = "Absolute path to the project's root directory to scan", required = true) String rootPath) {
        try {
            int count = repositoryScanner.scan(projectScope, Path.of(rootPath));
            return Map.of("scanned", count, "projectScope", projectScope);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
