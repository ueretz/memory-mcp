package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.entity.AgentTask;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {

    List<AgentTask> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    Optional<AgentTask> findByIdAndTaskId(Long id, Long taskId);

    /**
     * Atomic compare-and-set: TODO -> IN_PROGRESS, only if the row is still TODO and (has no
     * dependency, or its dependency is DONE). Returns 0 or 1 - the caller distinguishes "doesn't
     * exist," "not TODO," and "dependency unmet" by re-reading the row when this returns 0.
     * clearAutomatically = true because this is a native bulk UPDATE that bypasses the
     * persistence context - without it, a findByIdAndTaskId in the same transaction right after
     * this call could return a stale cached entity instead of the row this just wrote.
     * @Transactional is required here (rather than relying on a caller's transaction) because
     * @Modifying queries must run inside an active JPA transaction, and callers of this method -
     * including the concurrency test, which deliberately avoids @Transactional so each thread
     * commits independently on its own connection - may not provide one.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE agent_tasks SET status = 'IN_PROGRESS', updated_at = :now " +
            "WHERE id = :id AND task_id = :taskId AND status = 'TODO' " +
            "AND (depends_on_id IS NULL OR depends_on_id IN (SELECT id FROM agent_tasks WHERE status = 'DONE'))",
            nativeQuery = true)
    int claimIfAvailable(@Param("id") Long id, @Param("taskId") Long taskId, @Param("now") Instant now);

    @Query(value = "SELECT * FROM agent_tasks a WHERE a.task_id = :taskId AND a.status = 'TODO' " +
            "AND (a.depends_on_id IS NULL OR a.depends_on_id IN (SELECT id FROM agent_tasks WHERE status = 'DONE')) " +
            "ORDER BY a.created_at ASC",
            nativeQuery = true)
    List<AgentTask> findClaimable(@Param("taskId") Long taskId);
}
