package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.TaskSummary;
import ru.iuribabalin.memorymcp.entity.Task;
import ru.iuribabalin.memorymcp.repository.TaskRepository;

import java.time.Instant;
import java.util.List;

@Service
public class TaskService {

    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
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

    Task resolve(String projectScope, String taskKey) {
        return taskRepository.findByProjectScopeAndTaskKey(projectScope, taskKey)
                .orElseThrow(() -> new TaskNotFoundException(projectScope, taskKey));
    }

    private TaskSummary toSummary(Task task) {
        return new TaskSummary(task.getTaskKey(), task.getTitle(), task.getSource(), task.getStatus(), task.getUpdatedAt());
    }
}
