package ru.iuribabalin.memorymcp.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.iuribabalin.memorymcp.dto.TaskDetail;
import ru.iuribabalin.memorymcp.dto.TaskSummary;
import ru.iuribabalin.memorymcp.entity.Task;
import ru.iuribabalin.memorymcp.entity.UsageEvent;
import ru.iuribabalin.memorymcp.service.TaskService;
import ru.iuribabalin.memorymcp.service.UsageEventRecorder;

import java.util.List;
import java.util.Map;

@Component
public class TaskMcpTools {

    private final TaskService taskService;
    private final UsageEventRecorder usageEventRecorder;

    public TaskMcpTools(TaskService taskService, UsageEventRecorder usageEventRecorder) {
        this.taskService = taskService;
        this.usageEventRecorder = usageEventRecorder;
    }

    @McpTool(name = "task_start",
            description = "Create or resume a task folder under a project. ALWAYS ask the user explicitly " +
                    "whether current work belongs to a task before calling this - never infer silently. The " +
                    "task is identified by its ticket/task key (e.g. a Jira key) - resolve it via a ticket-tracker " +
                    "MCP tool if one is available in this session, otherwise ask the user for the key directly. " +
                    "Idempotent: calling again with the same projectScope+taskKey resumes the existing task. " +
                    "Keep the title short. Save the full markdown spec with memory_save and link that entry with " +
                    "specEntryName here or task_spec_set; the final result also belongs in the linked entry.")
    public TaskSummary taskStart(
            @McpToolParam(description = "Project identifier, auto-derived from the git repo name", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key, e.g. a Jira key or any user-given task number", required = true) String taskKey,
            @McpToolParam(description = "Short task title/summary, from the ticket tracker or the user - not the full spec", required = false) String title,
            @McpToolParam(description = "MANUAL if the user gave the key directly, JIRA if resolved via a ticket-tracker tool", required = false) Task.Source source,
            @McpToolParam(description = "Name of an existing memory entry containing the task's markdown spec. Save it with memory_save first; omit to leave the current spec link unchanged.", required = false) String specEntryName) {
        TaskSummary result = taskService.start(projectScope, taskKey, title, source);
        if (specEntryName != null) {
            TaskDetail detail = taskService.setSpec(projectScope, taskKey, specEntryName);
            result = new TaskSummary(detail.taskKey(), detail.title(), detail.source(), detail.status(),
                    detail.progress(), detail.spec() == null ? null : detail.spec().name(), detail.updatedAt());
        }
        usageEventRecorder.record(UsageEvent.Action.TASK_START, null, projectScope, taskKey, null);
        return result;
    }

    @McpTool(name = "task_spec_set",
            description = "Link an existing memory entry as the task's markdown spec. Save the full spec with " +
                    "memory_save first, then pass its entry name. Update the same linked entry with the final " +
                    "result when the task finishes; the task title itself remains only a short label.")
    public TaskDetail taskSpecSet(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key", required = true) String taskKey,
            @McpToolParam(description = "Name of the existing memory entry containing the markdown spec", required = true) String entryName) {
        TaskDetail result = taskService.setSpec(projectScope, taskKey, entryName);
        usageEventRecorder.record(UsageEvent.Action.TASK_SPEC_SET, entryName, projectScope, taskKey, null);
        return result;
    }

    @McpTool(name = "task_list",
            description = "List tasks under a project - use this to check whether a task already exists " +
                    "before creating a duplicate, or to show what's been worked on.")
    public List<TaskSummary> taskList(
            @McpToolParam(description = "Project identifier", required = true) String projectScope) {
        return taskService.list(projectScope);
    }

    @McpTool(name = "task_close",
            description = "Mark a task as done once its work is complete. Save the final result in the task's " +
                    "linked markdown spec entry with memory_save before closing it.")
    public Map<String, Object> taskClose(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key", required = true) String taskKey) {
        boolean closed = taskService.close(projectScope, taskKey);
        usageEventRecorder.record(UsageEvent.Action.TASK_CLOSE, null, projectScope, taskKey, null);
        return Map.of("closed", closed, "taskKey", taskKey);
    }

    @McpTool(name = "task_cancel",
            description = "Mark a task as CANCELLED instead of done. Write the cancellation reason into the " +
                    "task's own linked markdown spec entry with memory_save before calling this tool; the " +
                    "reason is deliberately not a parameter here.")
    public Map<String, Object> taskCancel(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key", required = true) String taskKey) {
        boolean cancelled = taskService.cancel(projectScope, taskKey);
        usageEventRecorder.record(UsageEvent.Action.TASK_CANCEL, null, projectScope, taskKey, null);
        return Map.of("cancelled", cancelled, "taskKey", taskKey);
    }

    @McpTool(name = "task_delete",
            description = "Permanently delete a task and everything scoped to it (its entries, folders, and " +
                    "agent-task board) - not the same as task_close, which only marks it done. Use only when the " +
                    "user explicitly wants the task's data gone, not just finished.")
    public Map<String, Object> taskDelete(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key", required = true) String taskKey) {
        boolean deleted = taskService.delete(projectScope, taskKey);
        usageEventRecorder.record(UsageEvent.Action.TASK_DELETE, null, projectScope, taskKey, null);
        return Map.of("deleted", deleted, "taskKey", taskKey);
    }
}
