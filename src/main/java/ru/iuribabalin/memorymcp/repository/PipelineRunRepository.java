package ru.iuribabalin.memorymcp.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.iuribabalin.memorymcp.entity.PipelineRun;

import java.util.List;
import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, Long> {
    List<PipelineRun> findByPipelineIdOrderByStartedAtDesc(Long pipelineId);
    Optional<PipelineRun> findFirstByPipelineIdOrderByStartedAtDesc(Long pipelineId);

    /**
     * Row-locked read for step updates: parallel branches finish concurrently and each update
     * rewrites the run's active-step set, so the read-modify-write must be serialized per run.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PipelineRun r where r.id = :id")
    Optional<PipelineRun> findByIdForUpdate(@Param("id") Long id);
}
