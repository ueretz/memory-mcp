package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.AgentTask;

import java.util.List;
import java.util.Optional;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {

    List<AgentTask> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    Optional<AgentTask> findByIdAndTaskId(Long id, Long taskId);
}
