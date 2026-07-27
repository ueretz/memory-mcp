package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.ProjectSummary;
import ru.iuribabalin.memorymcp.repository.MemoryNodeRepository;
import ru.iuribabalin.memorymcp.repository.TaskRepository;

import java.util.List;

@Service
public class ProjectService {

    private final MemoryNodeRepository nodeRepository;
    private final TaskRepository taskRepository;

    public ProjectService(MemoryNodeRepository nodeRepository, TaskRepository taskRepository) {
        this.nodeRepository = nodeRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional(readOnly = true)
    public List<ProjectSummary> list() {
        return nodeRepository.findAllProjectScopes().stream()
                .distinct()
                .sorted()
                .map(scope -> new ProjectSummary(
                        scope,
                        nodeRepository.countByProjectScopeAndTaskIsNull(scope),
                        taskRepository.countByProjectScope(scope)))
                .toList();
    }
}
