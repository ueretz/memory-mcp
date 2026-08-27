package ru.iuribabalin.memorymcp.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.iuribabalin.memorymcp.dto.AgentTaskSummary;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.entity.UsageEvent;
import ru.iuribabalin.memorymcp.service.AgentTaskService;
import ru.iuribabalin.memorymcp.service.UsageEventRecorder;

import java.util.List;
import java.util.Map;

@Component
public class AgentTaskMcpTools {

    private final AgentTaskService agentTaskService;
    private final UsageEventRecorder usageEventRecorder;

    public AgentTaskMcpTools(AgentTaskService agentTaskService, UsageEventRecorder usageEventRecorder) {
        this.agentTaskService = agentTaskService;
        this.usageEventRecorder = usageEventRecorder;
    }

    @McpTool(name = "agent_task_create",
            description = "Create a subtask on the agent task board for a task, breaking its work into a smaller " +
                    "tracked unit. Not idempotent - call agent_task_list first if you want to check for an existing " +
                    "duplicate before creating one. New subtasks start in TODO status.")
    public AgentTaskSummary agentTaskCreate(
            @McpToolParam(description = "Project identifier, auto-derived from the git repo name", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key this subtask belongs to - must already exist via task_start", required = true) String taskKey,
            @McpToolParam(description = "Short subtask title", required = true) String title,
            @McpToolParam(description = "Subtask category: ANALYSIS, IMPLEMENTATION, TESTING, REVIEW, or REPORTING", required = true) AgentTask.Type type,
            @McpToolParam(description = "Markdown notes/analysis for this subtask - what it covers, findings so far", required = false) String description) {
        AgentTaskSummary result = agentTaskService.create(projectScope, taskKey, title, type, description, null);
        usageEventRecorder.record(UsageEvent.Action.AGENT_TASK_CREATE, null, projectScope, taskKey, null);
        return result;
    }

    @McpTool(name = "agent_task_list",
            description = "List the subtasks on a task's agent task board, optionally filtered by type or status - " +
                    "use before agent_task_create to check for duplicates, or to see what's left to do.")
    public List<AgentTaskSummary> agentTaskList(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key", required = true) String taskKey,
            @McpToolParam(description = "Filter by subtask category", required = false) AgentTask.Type type,
            @McpToolParam(description = "Filter by status", required = false) AgentTask.Status status) {
        return agentTaskService.list(projectScope, taskKey, type, status, false);
    }

    @McpTool(name = "agent_task_update",
            description = "Move a subtask's status and/or update its title/analysis notes. This is the main tool for " +
                    "driving the board: set status to IN_PROGRESS before starting work on a subtask, DONE (with a " +
                    "result summary in description) when it's finished, or BLOCKED (with the reason in description) " +
                    "if it's stuck. All fields except agentTaskId are optional - only given fields change.")
    public AgentTaskSummary agentTaskUpdate(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key", required = true) String taskKey,
            @McpToolParam(description = "The subtask's id, from agent_task_create or agent_task_list", required = true) Long agentTaskId,
            @McpToolParam(description = "New status: TODO, IN_PROGRESS, DONE, or BLOCKED", required = false) AgentTask.Status status,
            @McpToolParam(description = "New title", required = false) String title,
            @McpToolParam(description = "New/appended markdown notes", required = false) String description) {
        AgentTaskSummary result = agentTaskService.update(projectScope, taskKey, agentTaskId, status, title, description);
        usageEventRecorder.record(UsageEvent.Action.AGENT_TASK_UPDATE, null, projectScope, taskKey, null);
        return result;
    }

    @McpTool(name = "agent_task_delete",
            description = "Remove a stale or duplicate subtask from the agent task board.")
    public Map<String, Object> agentTaskDelete(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key", required = true) String taskKey,
            @McpToolParam(description = "The subtask's id", required = true) Long agentTaskId) {
        agentTaskService.delete(projectScope, taskKey, agentTaskId);
        usageEventRecorder.record(UsageEvent.Action.AGENT_TASK_DELETE, null, projectScope, taskKey, null);
        return Map.of("deleted", true, "id", agentTaskId);
    }
}
