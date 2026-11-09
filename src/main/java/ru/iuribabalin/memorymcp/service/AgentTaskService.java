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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
            agentTask.setSpecEntryId(resolveSpecEntryId(task, specEntryName));
        }
        agentTask.setDependsOnId(dependsOnId);
        agentTask.setCreatedAt(now);
        agentTask.setUpdatedAt(now);
        return toSummary(agentTaskRepository.save(agentTask));
    }

    @Transactional
    public AgentTaskSummary setSpec(String projectScope, String taskKey, Long agentTaskId, String entryName) {
        AgentTask agentTask = resolveOwned(projectScope, taskKey, agentTaskId);
        agentTask.setSpecEntryId(resolveSpecEntryId(taskService.resolve(projectScope, taskKey), entryName));
        agentTask.setUpdatedAt(Instant.now());
        return toSummary(agentTaskRepository.save(agentTask));
    }

    @Transactional(readOnly = true)
    public List<AgentTaskSummary> list(String projectScope, String taskKey, AgentTask.Type typeFilter, AgentTask.Status statusFilter, boolean claimable) {
        Task task = taskService.resolve(projectScope, taskKey);
        List<AgentTask> source = claimable
                ? agentTaskRepository.findClaimable(task.getId())
                : agentTaskRepository.findByTaskIdOrderByCreatedAtAsc(task.getId());
        List<AgentTask> visible = source.stream()
                .filter(agentTask -> typeFilter == null || agentTask.getType() == typeFilter)
                .filter(agentTask -> claimable || statusFilter == null || agentTask.getStatus() == statusFilter)
                .toList();
        // One lookup for the whole board instead of one per subtask that has a spec - the board
        // page and agent_task_list both go through here.
        Map<Long, SpecSummary> specs = specsByEntryId(visible);
        return visible.stream()
                .map(agentTask -> toSummary(agentTask,
                        agentTask.getSpecEntryId() == null ? null : specs.get(agentTask.getSpecEntryId())))
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

    private Long resolveSpecEntryId(Task task, String entryName) {
        return taskService.resolveSpecEntry(task, entryName);
    }

    private SpecSummary spec(AgentTask agentTask) {
        if (agentTask.getSpecEntryId() == null) {
            return null;
        }
        return memoryNodeRepository.findById(agentTask.getSpecEntryId())
                .map(node -> new SpecSummary(node.getName(), node.getDescription(), node.getUpdatedAt()))
                .orElse(null);
    }

    private Map<Long, SpecSummary> specsByEntryId(List<AgentTask> agentTasks) {
        List<Long> entryIds = agentTasks.stream()
                .map(AgentTask::getSpecEntryId)
                .filter(Objects::nonNull)
                .toList();
        if (entryIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, SpecSummary> specs = new HashMap<>();
        memoryNodeRepository.findAllById(entryIds).forEach(node ->
                specs.put(node.getId(), new SpecSummary(node.getName(), node.getDescription(), node.getUpdatedAt())));
        return specs;
    }

    /** Same shape as {@link #toSummary(AgentTask)}, but served from {@link #list}'s batch lookup. */
    private AgentTaskSummary toSummary(AgentTask agentTask, SpecSummary spec) {
        return new AgentTaskSummary(
                agentTask.getId(),
                agentTask.getTitle(),
                agentTask.getType(),
                agentTask.getStatus(),
                agentTask.getDescription(),
                spec,
                agentTask.getUpdatedAt(),
                agentTask.getDependsOnId());
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
