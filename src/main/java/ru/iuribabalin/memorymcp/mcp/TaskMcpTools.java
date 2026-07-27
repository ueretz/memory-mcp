package ru.iuribabalin.memorymcp.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.iuribabalin.memorymcp.dto.TaskSummary;
import ru.iuribabalin.memorymcp.entity.Task;
import ru.iuribabalin.memorymcp.service.TaskService;

import java.util.List;
import java.util.Map;

@Component
public class TaskMcpTools {

    private final TaskService taskService;

    public TaskMcpTools(TaskService taskService) {
        this.taskService = taskService;
    }

    @McpTool(name = "task_start",
            description = "Create or resume a task folder under a project. ALWAYS ask the user explicitly " +
                    "whether current work belongs to a task before calling this - never infer silently. The " +
                    "task is identified by its ticket/task key (e.g. a Jira key) - resolve it via a ticket-tracker " +
                    "MCP tool if one is available in this session, otherwise ask the user for the key directly. " +
                    "Idempotent: calling again with the same projectScope+taskKey resumes the existing task.")
    public TaskSummary taskStart(
            @McpToolParam(description = "Project identifier, auto-derived from the git repo name", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key, e.g. a Jira key or any user-given task number", required = true) String taskKey,
            @McpToolParam(description = "Task title/summary, from the ticket tracker or the user", required = false) String title,
            @McpToolParam(description = "MANUAL if the user gave the key directly, JIRA if resolved via a ticket-tracker tool", required = false) Task.Source source) {
        return taskService.start(projectScope, taskKey, title, source);
    }

    @McpTool(name = "task_list",
            description = "List tasks under a project - use this to check whether a task already exists " +
                    "before creating a duplicate, or to show what's been worked on.")
    public List<TaskSummary> taskList(
            @McpToolParam(description = "Project identifier", required = true) String projectScope) {
        return taskService.list(projectScope);
    }

    @McpTool(name = "task_close",
            description = "Mark a task as done once its work is complete.")
    public Map<String, Object> taskClose(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key", required = true) String taskKey) {
        boolean closed = taskService.close(projectScope, taskKey);
        return Map.of("closed", closed, "taskKey", taskKey);
    }
}
