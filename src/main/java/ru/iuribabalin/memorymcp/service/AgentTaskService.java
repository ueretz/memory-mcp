package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.AgentTaskSummary;
import ru.iuribabalin.memorymcp.dto.SpecSummary;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.entity.Task;
import ru.iuribabalin.memorymcp.repository.AgentTaskRepository;
import ru.iuribabalin.memorymcp.repository.MemoryNodeRepository;

import java.time.Instant;
import java.util.List;

@Service
public class AgentTaskService {

    private final AgentTaskRepository agentTaskRepository;
    private final TaskService taskService;
    private final MemoryNodeRepository memoryNodeRepository;

    public AgentTaskService(AgentTaskRepository agentTaskRepository, TaskService taskService,
                            MemoryNodeRepository memoryNodeRepository) {
        this.agentTaskRepository = agentTaskRepository;
        this.taskService = taskService;
        this.memoryNodeRepository = memoryNodeRepository;
    }

    @Transactional
    public AgentTaskSummary create(String projectScope, String taskKey, String title, AgentTask.Type type, String description, Long dependsOnId) {
        return create(projectScope, taskKey, title, type, description, dependsOnId, null);
    }

    @Transactional
    public AgentTaskSummary create(String projectScope, String taskKey, String title, AgentTask.Type type,
                                   String description, Long dependsOnId, String specEntryName) {
        Task task = taskService.resolve(projectScope, taskKey);
        if (dependsOnId != null) {
            agentTaskRepository.findByIdAndTaskId(dependsOnId, task.getId())
                    .orElseThrow(() -> new AgentTaskNotFoundException(projectScope, taskKey, dependsOnId));
        }
        Instant now = Instant.now();
        AgentTask agentTask = new AgentTask();
        agentTask.setTaskId(task.getId());
        agentTask.setTitle(title);
        agentTask.setType(type);
        agentTask.setStatus(AgentTask.Status.TODO);
        agentTask.setDescription(description);
        if (specEntryName != null) {
            agentTask.setSpecEntryId(resolveSpecEntryId(specEntryName));
        }
        agentTask.setDependsOnId(dependsOnId);
        agentTask.setCreatedAt(now);
        agentTask.setUpdatedAt(now);
        return toSummary(agentTaskRepository.save(agentTask));
    }

    @Transactional
    public AgentTaskSummary setSpec(String projectScope, String taskKey, Long agentTaskId, String entryName) {
        AgentTask agentTask = resolveOwned(projectScope, taskKey, agentTaskId);
        agentTask.setSpecEntryId(resolveSpecEntryId(entryName));
        agentTask.setUpdatedAt(Instant.now());
        return toSummary(agentTaskRepository.save(agentTask));
    }

    @Transactional(readOnly = true)
    public List<AgentTaskSummary> list(String projectScope, String taskKey, AgentTask.Type typeFilter, AgentTask.Status statusFilter, boolean claimable) {
        Task task = taskService.resolve(projectScope, taskKey);
        List<AgentTask> source = claimable
                ? agentTaskRepository.findClaimable(task.getId())
                : agentTaskRepository.findByTaskIdOrderByCreatedAtAsc(task.getId());
        return source.stream()
                .filter(agentTask -> typeFilter == null || agentTask.getType() == typeFilter)
                .filter(agentTask -> claimable || statusFilter == null || agentTask.getStatus() == statusFilter)
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public AgentTaskSummary claim(String projectScope, String taskKey, Long agentTaskId) {
        Task task = taskService.resolve(projectScope, taskKey);
        int updated = agentTaskRepository.claimIfAvailable(agentTaskId, task.getId(), Instant.now());
        if (updated == 0) {
            AgentTask existing = agentTaskRepository.findByIdAndTaskId(agentTaskId, task.getId())
                    .orElseThrow(() -> new AgentTaskNotFoundException(projectScope, taskKey, agentTaskId));
            if (existing.getStatus() != AgentTask.Status.TODO) {
                throw new AgentTaskNotClaimableException(
                        "Agent task %d is %s, not TODO - it's already been claimed".formatted(agentTaskId, existing.getStatus()));
            }
            throw new AgentTaskNotClaimableException(
                    "Agent task %d depends on %d, which is not DONE yet".formatted(agentTaskId, existing.getDependsOnId()));
        }
        return toSummary(agentTaskRepository.findByIdAndTaskId(agentTaskId, task.getId())
                .orElseThrow(() -> new AgentTaskNotFoundException(projectScope, taskKey, agentTaskId)));
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

    private Long resolveSpecEntryId(String entryName) {
        return memoryNodeRepository.findByName(entryName)
                .orElseThrow(() -> new MemoryNotFoundException(entryName))
                .getId();
    }

    private SpecSummary spec(AgentTask agentTask) {
        if (agentTask.getSpecEntryId() == null) {
            return null;
        }
        return memoryNodeRepository.findById(agentTask.getSpecEntryId())
                .map(node -> new SpecSummary(node.getName(), node.getDescription(), node.getUpdatedAt()))
                .orElse(null);
    }

    private AgentTaskSummary toSummary(AgentTask agentTask) {
        return new AgentTaskSummary(
                agentTask.getId(),
                agentTask.getTitle(),
                agentTask.getType(),
                agentTask.getStatus(),
                agentTask.getDescription(),
                spec(agentTask),
                agentTask.getUpdatedAt(),
                agentTask.getDependsOnId());
    }
}
