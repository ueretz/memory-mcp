package ru.iuribabalin.memorymcp.ui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.AgentTaskSummary;
import ru.iuribabalin.memorymcp.dto.ProjectSummary;
import ru.iuribabalin.memorymcp.dto.TaskSummary;
import ru.iuribabalin.memorymcp.service.AgentTaskService;
import ru.iuribabalin.memorymcp.service.ProjectService;
import ru.iuribabalin.memorymcp.service.TaskService;

import java.util.List;

@RestController
public class ProjectViewController {

    private final ProjectService projectService;
    private final TaskService taskService;
    private final AgentTaskService agentTaskService;

    public ProjectViewController(ProjectService projectService, TaskService taskService, AgentTaskService agentTaskService) {
        this.projectService = projectService;
        this.taskService = taskService;
        this.agentTaskService = agentTaskService;
    }

    @GetMapping("/api/projects")
    public List<ProjectSummary> list() {
        return projectService.list();
    }

    @GetMapping("/api/projects/{projectScope}/tasks")
    public List<TaskSummary> tasks(@PathVariable String projectScope) {
        return taskService.list(projectScope);
    }

    @GetMapping("/api/projects/{projectScope}/tasks/{taskKey}/agent-tasks")
    public List<AgentTaskSummary> agentTasks(@PathVariable String projectScope, @PathVariable String taskKey) {
        return agentTaskService.list(projectScope, taskKey, null, null);
    }
}
