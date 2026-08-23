package ru.iuribabalin.memorymcp.ui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.FolderSummary;
import ru.iuribabalin.memorymcp.service.FolderService;

import java.util.List;

@RestController
public class FolderViewController {

    private final FolderService folderService;

    public FolderViewController(FolderService folderService) {
        this.folderService = folderService;
    }

    @GetMapping("/api/folders")
    public List<FolderSummary> list(
            @RequestParam String projectScope,
            @RequestParam(required = false) String taskKey,
            @RequestParam(required = false) String parent) {
        return folderService.listChildren(projectScope, taskKey, parent);
    }

    @GetMapping("/api/folders/{name}")
    public FolderSummary get(@PathVariable String name) {
        return folderService.get(name);
    }
}
