package ru.iuribabalin.memorymcp.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.entity.Folder;
import ru.iuribabalin.memorymcp.entity.MemoryNode;
import ru.iuribabalin.memorymcp.entity.Task;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TaskDeleteCascadeTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private MemoryNodeRepository nodeRepository;

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private AgentTaskRepository agentTaskRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void deletingATaskCascadesToItsEntriesFoldersAndAgentTasks() {
        Instant now = Instant.now();

        Task task = new Task();
        task.setProjectScope("task-cascade-test-project");
        task.setTaskKey("CASCADE-1");
        task.setTitle("Cascade test task");
        task.setSource(Task.Source.MANUAL);
        task.setStatus(Task.Status.ACTIVE);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        Task savedTask = taskRepository.saveAndFlush(task);

        MemoryNode node = new MemoryNode();
        node.setName("task-cascade-test-entry");
        node.setType(MemoryNode.Type.PROJECT);
        node.setDescription("desc");
        node.setContent("content");
        node.setProjectScope("task-cascade-test-project");
        node.setTask(savedTask);
        node.setCreatedAt(now);
        node.setUpdatedAt(now);
        MemoryNode savedNode = nodeRepository.saveAndFlush(node);

        Folder folder = new Folder();
        folder.setName("task-cascade-test-folder");
        folder.setDescription("desc");
        folder.setProjectScope("task-cascade-test-project");
        folder.setTask(savedTask);
        folder.setCreatedAt(now);
        folder.setUpdatedAt(now);
        Folder savedFolder = folderRepository.saveAndFlush(folder);

        AgentTask agentTask = new AgentTask();
        agentTask.setTaskId(savedTask.getId());
        agentTask.setTitle("Cascade test subtask");
        agentTask.setType(AgentTask.Type.ANALYSIS);
        agentTask.setStatus(AgentTask.Status.TODO);
        agentTask.setCreatedAt(now);
        agentTask.setUpdatedAt(now);
        AgentTask savedAgentTask = agentTaskRepository.saveAndFlush(agentTask);

        entityManager.clear();
        Task taskToDelete = taskRepository.findById(savedTask.getId()).orElseThrow();
        taskRepository.delete(taskToDelete);
        taskRepository.flush();
        entityManager.clear();

        assertThat(nodeRepository.findById(savedNode.getId())).isEmpty();
        assertThat(folderRepository.findById(savedFolder.getId())).isEmpty();
        assertThat(agentTaskRepository.findById(savedAgentTask.getId())).isEmpty();
    }
}
