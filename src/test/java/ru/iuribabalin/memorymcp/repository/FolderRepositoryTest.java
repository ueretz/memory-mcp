package ru.iuribabalin.memorymcp.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.entity.Folder;
import ru.iuribabalin.memorymcp.entity.Task;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class FolderRepositoryTest {

    @Autowired
    private FolderRepository repository;

    @Autowired
    private TaskRepository taskRepository;

    @Test
    void listsDirectChildrenOnly() {
        Folder root = save("folder-repo-test-root", null);
        Folder child = save("folder-repo-test-child", root);
        save("folder-repo-test-grandchild", child);

        List<Folder> topLevel = repository.listChildren(null, "folder-repo-test-project", null);
        assertThat(topLevel).extracting(Folder::getName).contains("folder-repo-test-root");

        List<Folder> children = repository.listChildren("folder-repo-test-root", "folder-repo-test-project", null);
        assertThat(children).extracting(Folder::getName).containsExactly("folder-repo-test-child");
    }

    @Test
    void filtersOutTaskScopedFoldersWhenTaskIdIsNull() {
        // Create a project-common (task=null) folder
        Folder commonFolder = save("folder-repo-test-common", null);

        // Create a Task and a folder scoped to that task
        Task task = new Task();
        task.setProjectScope("folder-repo-test-project");
        task.setTaskKey("TEST-123");
        task.setTitle("Test Task");
        task.setSource(Task.Source.MANUAL);
        task.setStatus(Task.Status.ACTIVE);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        Task savedTask = taskRepository.saveAndFlush(task);

        Folder taskScopedFolder = new Folder();
        taskScopedFolder.setName("folder-repo-test-task-scoped");
        taskScopedFolder.setDescription("desc");
        taskScopedFolder.setProjectScope("folder-repo-test-project");
        taskScopedFolder.setTask(savedTask);  // scoped to a specific task
        taskScopedFolder.setCreatedAt(Instant.now());
        taskScopedFolder.setUpdatedAt(Instant.now());
        repository.saveAndFlush(taskScopedFolder);

        // When listing top-level folders with taskId=null, should only get the project-common folder
        List<Folder> topLevel = repository.listChildren(null, "folder-repo-test-project", null);
        assertThat(topLevel).extracting(Folder::getName).containsExactly("folder-repo-test-common");

        // When listing top-level folders with a specific taskId, should only get the task-scoped folder
        List<Folder> taskFolders = repository.listChildren(null, "folder-repo-test-project", savedTask.getId());
        assertThat(taskFolders).extracting(Folder::getName).containsExactly("folder-repo-test-task-scoped");
    }

    private Folder save(String name, Folder parent) {
        Folder folder = new Folder();
        folder.setName(name);
        folder.setDescription("desc");
        folder.setProjectScope("folder-repo-test-project");
        folder.setParent(parent);
        folder.setCreatedAt(Instant.now());
        folder.setUpdatedAt(Instant.now());
        return repository.saveAndFlush(folder);
    }
}
