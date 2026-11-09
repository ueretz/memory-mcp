package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.TaskDetail;
import ru.iuribabalin.memorymcp.dto.TaskSummary;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.entity.Task;
import ru.iuribabalin.memorymcp.repository.AgentTaskRepository;
import ru.iuribabalin.memorymcp.repository.MemoryNodeRepository;
import ru.iuribabalin.memorymcp.repository.TaskRepository;

import java.time.Instant;
import java.util.List;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final AgentTaskRepository agentTaskRepository;
    private final MemoryNodeRepository memoryNodeRepository;

    public TaskService(TaskRepository taskRepository, AgentTaskRepository agentTaskRepository,
                       MemoryNodeRepository memoryNodeRepository) {
        this.taskRepository = taskRepository;
        this.agentTaskRepository = agentTaskRepository;
        this.memoryNodeRepository = memoryNodeRepository;
    }

    @Transactional
    public TaskSummary start(String projectScope, String taskKey, String title, Task.Source source) {
        Instant now = Instant.now();
        Task task = taskRepository.findByProjectScopeAndTaskKey(projectScope, taskKey).orElseGet(Task::new);
        boolean isNew = task.getId() == null;
        task.setProjectScope(projectScope);
        task.setTaskKey(taskKey);
        if (title != null) {
            task.setTitle(title);
        }
        task.setSource(source != null ? source : Task.Source.MANUAL);
        if (isNew) {
            task.setStatus(Task.Status.ACTIVE);
            task.setCreatedAt(now);
        }
        task.setUpdatedAt(now);
        task = taskRepository.save(task);
        return toSummary(task);
    }

    @Transactional(readOnly = true)
    public List<TaskSummary> list(String projectScope) {
        return taskRepository.findByProjectScopeOrderByUpdatedAtDesc(projectScope).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskDetail get(String projectScope, String taskKey) {
        return toDetail(resolve(projectScope, taskKey));
    }

    @Transactional
    public TaskDetail setSpec(String projectScope, String taskKey, String entryName) {
        Task task = resolve(projectScope, taskKey);
        Long specEntryId = memoryNodeRepository.findByName(entryName)
                .orElseThrow(() -> new MemoryNotFoundException(entryName))
                .getId();
        task.setSpecEntryId(specEntryId);
        task.setUpdatedAt(Instant.now());
        return toDetail(taskRepository.save(task));
    }

    @Transactional
    public boolean close(String projectScope, String taskKey) {
        return taskRepository.findByProjectScopeAndTaskKey(projectScope, taskKey)
                .map(task -> {
                    task.setStatus(Task.Status.DONE);
                    task.setUpdatedAt(Instant.now());
                    taskRepository.save(task);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public boolean cancel(String projectScope, String taskKey) {
        return taskRepository.findByProjectScopeAndTaskKey(projectScope, taskKey)
                .map(task -> {
                    task.setStatus(Task.Status.CANCELLED);
                    task.setUpdatedAt(Instant.now());
                    taskRepository.save(task);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public boolean delete(String projectScope, String taskKey) {
        return taskRepository.findByProjectScopeAndTaskKey(projectScope, taskKey)
                .map(task -> {
                    taskRepository.delete(task);
                    return true;
                })
                .orElse(false);
    }

    Task resolve(String projectScope, String taskKey) {
        return taskRepository.findByProjectScopeAndTaskKey(projectScope, taskKey)
                .orElseThrow(() -> new TaskNotFoundException(projectScope, taskKey));
    }

    private TaskSummary toSummary(Task task) {
        TaskDetail.Spec spec = spec(task);
        return new TaskSummary(
                task.getTaskKey(),
                task.getTitle(),
                task.getSource(),
                task.getStatus(),
                progress(task),
                spec != null ? spec.name() : null,
                task.getUpdatedAt());
    }

    private TaskDetail toDetail(Task task) {
        return new TaskDetail(
                task.getTaskKey(),
                task.getTitle(),
                task.getSource(),
                task.getStatus(),
                spec(task),
                progress(task),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    private TaskDetail.Spec spec(Task task) {
        if (task.getSpecEntryId() == null) {
            return null;
        }
        return memoryNodeRepository.findById(task.getSpecEntryId())
                .map(node -> new TaskDetail.Spec(node.getName(), node.getDescription(), node.getUpdatedAt()))
                .orElse(null);
    }

    private TaskDetail.Progress progress(Task task) {
        long total = agentTaskRepository.countByTaskId(task.getId());
        long done = agentTaskRepository.countByTaskIdAndStatus(task.getId(), AgentTask.Status.DONE);
        int percent = total == 0
                ? task.getStatus() == Task.Status.DONE ? 100 : 0
                : (int) Math.round(100.0 * done / total);
        return new TaskDetail.Progress(done, total, percent);
    }
}
