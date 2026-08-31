package ru.iuribabalin.memorymcp.ui;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.GraphResponse;
import ru.iuribabalin.memorymcp.dto.MemoryEntryDetail;
import ru.iuribabalin.memorymcp.dto.MemoryEntrySummary;
import ru.iuribabalin.memorymcp.entity.MemoryNode;
import ru.iuribabalin.memorymcp.service.MemoryNotFoundException;
import ru.iuribabalin.memorymcp.service.MemoryService;

import java.util.List;

/**
 * Viewing surface for the local dashboard, plus delete - the one deliberate write capability
 * this otherwise-read-only dashboard has (see the deletion design spec for why).
 */
@RestController
public class MemoryViewController {

    private final MemoryService memoryService;

    public MemoryViewController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping("/api/memory")
    public List<MemoryEntrySummary> list(
            @RequestParam(required = false) MemoryNode.Type type,
            @RequestParam(required = false) String projectScope,
            @RequestParam(required = false) String taskKey,
            @RequestParam(required = false) String folder,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false, defaultValue = "0") int offset) {
        return memoryService.list(type, projectScope, taskKey, folder, limit, offset);
    }

    @GetMapping("/api/memory/graph")
    public GraphResponse graph(
            @RequestParam(required = false) MemoryNode.Type type,
            @RequestParam(required = false) String projectScope,
            @RequestParam(required = false) String taskKey) {
        return memoryService.graph(type, projectScope, taskKey);
    }

    @GetMapping("/api/memory/search")
    public List<MemoryEntrySummary> search(
            @RequestParam String q,
            @RequestParam(required = false) MemoryNode.Type type,
            @RequestParam(required = false) String projectScope,
            @RequestParam(required = false) String taskKey,
            @RequestParam(required = false) String folder,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return memoryService.search(q, type, projectScope, taskKey, folder, limit);
    }

    @GetMapping("/api/memory/{name}")
    public MemoryEntryDetail get(@PathVariable String name) {
        return memoryService.get(name);
    }

    @DeleteMapping("/api/memory/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        if (!memoryService.delete(name)) {
            throw new MemoryNotFoundException(name);
        }
        return ResponseEntity.noContent().build();
    }
}
