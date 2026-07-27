package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.Task;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    Optional<Task> findByProjectScopeAndTaskKey(String projectScope, String taskKey);

    List<Task> findByProjectScopeOrderByUpdatedAtDesc(String projectScope);

    long countByProjectScope(String projectScope);
}
