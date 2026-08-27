package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.AgentTaskSummary;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.entity.Task;
import ru.iuribabalin.memorymcp.repository.AgentTaskRepository;

import java.time.Instant;
import java.util.List;

@Service
public class AgentTaskService {

    private final AgentTaskRepository agentTaskRepository;
    private final TaskService taskService;

    public AgentTaskService(AgentTaskRepository agentTaskRepository, TaskService taskService) {
        this.agentTaskRepository = agentTaskRepository;
        this.taskService = taskService;
    }

    @Transactional
    public AgentTaskSummary create(String projectScope, String taskKey, String title, AgentTask.Type type, String description) {
        Task task = taskService.resolve(projectScope, taskKey);
        Instant now = Instant.now();
        AgentTask agentTask = new AgentTask();
        agentTask.setTaskId(task.getId());
        agentTask.setTitle(title);
        agentTask.setType(type);
        agentTask.setStatus(AgentTask.Status.TODO);
        agentTask.setDescription(description);
        agentTask.setCreatedAt(now);
        agentTask.setUpdatedAt(now);
        return toSummary(agentTaskRepository.save(agentTask));
    }

    @Transactional(readOnly = true)
    public List<AgentTaskSummary> list(String projectScope, String taskKey, AgentTask.Type typeFilter, AgentTask.Status statusFilter) {
        Task task = taskService.resolve(projectScope, taskKey);
        return agentTaskRepository.findByTaskIdOrderByCreatedAtAsc(task.getId()).stream()
                .filter(agentTask -> typeFilter == null || agentTask.getType() == typeFilter)
                .filter(agentTask -> statusFilter == null || agentTask.getStatus() == statusFilter)
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public AgentTaskSummary update(String projectScope, String taskKey, Long agentTaskId, AgentTask.Status status, String title, String description) {
        AgentTask agentTask = resolveOwned(projectScope, taskKey, agentTaskId);
        if (status != null) {
            agentTask.setStatus(status);
        }
        if (title != null) {
            agentTask.setTitle(title);
        }
        if (description != null) {
            agentTask.setDescription(description);
        }
        agentTask.setUpdatedAt(Instant.now());
        return toSummary(agentTaskRepository.save(agentTask));
    }

    @Transactional
    public void delete(String projectScope, String taskKey, Long agentTaskId) {
        agentTaskRepository.delete(resolveOwned(projectScope, taskKey, agentTaskId));
    }

    private AgentTask resolveOwned(String projectScope, String taskKey, Long agentTaskId) {
        Task task = taskService.resolve(projectScope, taskKey);
        return agentTaskRepository.findByIdAndTaskId(agentTaskId, task.getId())
                .orElseThrow(() -> new AgentTaskNotFoundException(projectScope, taskKey, agentTaskId));
    }

    private AgentTaskSummary toSummary(AgentTask agentTask) {
        return new AgentTaskSummary(
                agentTask.getId(),
                agentTask.getTitle(),
                agentTask.getType(),
                agentTask.getStatus(),
                agentTask.getDescription(),
                agentTask.getUpdatedAt());
    }
}
