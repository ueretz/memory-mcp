package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.SaveMemoryRequest;
import ru.iuribabalin.memorymcp.entity.MemoryNode;
import ru.iuribabalin.memorymcp.entity.Task;
import ru.iuribabalin.memorymcp.repository.FolderRepository;
import ru.iuribabalin.memorymcp.repository.MemoryNodeRepository;
import ru.iuribabalin.memorymcp.repository.TaskRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ProjectServiceTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private MemoryNodeRepository nodeRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private FolderRepository folderRepository;

    @Test
    void deleteRemovesEverythingScopedToTheProject() {
        String scope = "project-svc-test-delete-project";

        memoryService.save(new SaveMemoryRequest(
                "project-svc-test-delete-common-entry", MemoryNode.Type.PROJECT, "desc", "content", scope, null, null, null, null));
        folderService.create(scope, null, "project-svc-test-delete-common-folder", "desc", null, null);
        taskService.start(scope, "PSD-1", "Test task", Task.Source.MANUAL);
        memoryService.save(new SaveMemoryRequest(
                "project-svc-test-delete-task-entry", MemoryNode.Type.PROJECT, "desc", "content", scope, "PSD-1", null, null, null));
        folderService.create(scope, "PSD-1", "project-svc-test-delete-task-folder", "desc", null, null);

        projectService.delete(scope);

        assertThat(nodeRepository.findByName("project-svc-test-delete-common-entry")).isEmpty();
        assertThat(nodeRepository.findByName("project-svc-test-delete-task-entry")).isEmpty();
        assertThat(folderRepository.findByName("project-svc-test-delete-common-folder")).isEmpty();
        assertThat(folderRepository.findByName("project-svc-test-delete-task-folder")).isEmpty();
        assertThat(taskRepository.findByProjectScopeAndTaskKey(scope, "PSD-1")).isEmpty();
    }

    @Test
    void deleteOfANonExistentProjectIsANoOp() {
        projectService.delete("project-svc-test-delete-nonexistent-project");
    }
}
