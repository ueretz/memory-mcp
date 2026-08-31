package ru.iuribabalin.memorymcp.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.ProjectSummary;
import ru.iuribabalin.memorymcp.repository.FolderRepository;
import ru.iuribabalin.memorymcp.repository.MemoryNodeRepository;
import ru.iuribabalin.memorymcp.repository.TaskRepository;
import ru.iuribabalin.memorymcp.repository.UsageEventRepository;

import java.util.List;

@Service
public class ProjectService {

    private final MemoryNodeRepository nodeRepository;
    private final TaskRepository taskRepository;
    private final FolderRepository folderRepository;
    private final UsageEventRepository usageEventRepository;
    private final EntityManager entityManager;

    public ProjectService(MemoryNodeRepository nodeRepository, TaskRepository taskRepository,
                           FolderRepository folderRepository, UsageEventRepository usageEventRepository,
                           EntityManager entityManager) {
        this.nodeRepository = nodeRepository;
        this.taskRepository = taskRepository;
        this.folderRepository = folderRepository;
        this.usageEventRepository = usageEventRepository;
        this.entityManager = entityManager;
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

    /**
     * A project is a derived projectScope value, not a row - deletes every Task with this scope
     * first (cascades away everything scoped to each one), then whatever common-scope (task-less)
     * data is left, then usage-event history. Idempotent: an empty/nonexistent scope is a no-op,
     * not an error.
     *
     * <p>Clears the persistence context first: the derived {@code deleteBy...} repository methods
     * select-then-remove entities individually (relying on Postgres {@code ON DELETE CASCADE} for
     * the rest), and if an earlier operation in this same transaction left a task-scoped entity
     * (e.g. a MemoryNode) managed in-session, Hibernate's pre-flush check rejects the Task removal
     * as leaving a "transient reference" behind. Same pattern used in TaskDeleteCascadeTest.
     */
    @Transactional
    public void delete(String projectScope) {
        entityManager.clear();
        taskRepository.deleteByProjectScope(projectScope);
        nodeRepository.deleteByProjectScopeAndTaskIsNull(projectScope);
        folderRepository.deleteByProjectScopeAndTaskIsNull(projectScope);
        usageEventRepository.deleteByProjectScope(projectScope);
    }
}
