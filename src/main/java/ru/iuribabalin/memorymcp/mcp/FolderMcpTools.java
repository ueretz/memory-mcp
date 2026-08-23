package ru.iuribabalin.memorymcp.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.iuribabalin.memorymcp.dto.FolderSummary;
import ru.iuribabalin.memorymcp.entity.UsageEvent;
import ru.iuribabalin.memorymcp.service.FolderService;
import ru.iuribabalin.memorymcp.service.UsageEventRecorder;

import java.util.List;

@Component
public class FolderMcpTools {

    private final FolderService folderService;
    private final UsageEventRecorder usageEventRecorder;

    public FolderMcpTools(FolderService folderService, UsageEventRecorder usageEventRecorder) {
        this.folderService = folderService;
        this.usageEventRecorder = usageEventRecorder;
    }

    @McpTool(name = "folder_create",
            description = "Create or update a folder to organize memory entries under a project's common space or " +
                    "a task. Idempotent by name - calling again with the same name updates its description/parent. " +
                    "Call folder_list first to check whether a suitable folder already exists before creating a new " +
                    "one. Pass parentFolder to nest it inside another folder (must already exist, same project/task).")
    public FolderSummary folderCreate(
            @McpToolParam(description = "Project this folder belongs to, auto-derived from the git repo name", required = true) String projectScope,
            @McpToolParam(description = "Task key to scope this folder to a specific task; omit for a project-level common folder", required = false) String taskKey,
            @McpToolParam(description = "Unique kebab-case slug for this folder", required = true) String name,
            @McpToolParam(description = "One-line summary of what belongs in this folder", required = true) String description,
            @McpToolParam(description = "Name of an existing folder to nest this one inside; omit for a top-level folder", required = false) String parentFolder,
            @McpToolParam(description = "Who created this folder, e.g. 'Name <email>' - auto-derive from git config, never ask the user", required = false) String createdBy) {
        FolderSummary result = folderService.create(projectScope, taskKey, name, description, parentFolder, createdBy);
        usageEventRecorder.record(UsageEvent.Action.FOLDER_CREATE, name, projectScope, taskKey, createdBy);
        return result;
    }

    @McpTool(name = "folder_list",
            description = "List folders directly under a project's common space, a task, or another folder - use " +
                    "before folder_create to avoid duplicates, or to see how entries are already organized.")
    public List<FolderSummary> folderList(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "Task key filter; omit to list the project's common-space folders", required = false) String taskKey,
            @McpToolParam(description = "Parent folder name; omit to list top-level folders", required = false) String parentFolder) {
        return folderService.listChildren(projectScope, taskKey, parentFolder);
    }
}
