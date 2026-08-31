package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.entity.Task;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TaskServiceTest {

    @Autowired
    private TaskService taskService;

    @Test
    void deleteRemovesTheTask() {
        taskService.start("task-svc-test-delete-project", "TSD-1", "Test task", Task.Source.MANUAL);

        boolean deleted = taskService.delete("task-svc-test-delete-project", "TSD-1");

        assertThat(deleted).isTrue();
        assertThat(taskService.list("task-svc-test-delete-project")).isEmpty();
    }

    @Test
    void deleteReturnsFalseWhenTaskDoesNotExist() {
        boolean deleted = taskService.delete("task-svc-test-delete-project-2", "NO-SUCH-TASK");

        assertThat(deleted).isFalse();
    }
}
