package ru.iuribabalin.memorymcp.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.iuribabalin.memorymcp.entity.UsageEvent;
import ru.iuribabalin.memorymcp.service.ProjectService;
import ru.iuribabalin.memorymcp.service.UsageEventRecorder;

import java.util.Map;

@Component
public class ProjectMcpTools {

    private final ProjectService projectService;
    private final UsageEventRecorder usageEventRecorder;

    public ProjectMcpTools(ProjectService projectService, UsageEventRecorder usageEventRecorder) {
        this.projectService = projectService;
        this.usageEventRecorder = usageEventRecorder;
    }

    @McpTool(name = "project_delete",
            description = "PERMANENTLY delete an entire project - every task (and everything scoped to each), " +
                    "every common entry, every common folder. This is the most destructive tool on this server " +
                    "and cannot be undone. ALWAYS ask the user to explicitly confirm the exact project name " +
                    "before calling this - never infer or assume consent, the same way task_start always asks " +
                    "before scoping work to a task.")
    public Map<String, Object> projectDelete(
            @McpToolParam(description = "The project identifier to delete entirely", required = true) String projectScope) {
        projectService.delete(projectScope);
        usageEventRecorder.record(UsageEvent.Action.PROJECT_DELETE, null, projectScope, null, null);
        return Map.of("deleted", true, "projectScope", projectScope);
    }
}
