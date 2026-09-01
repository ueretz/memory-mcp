package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;

import java.util.List;
import java.util.Optional;

public interface PipelineRunStepRepository extends JpaRepository<PipelineRunStep, Long> {
    List<PipelineRunStep> findByRunIdOrderByOrderIndexAsc(Long runId);
    Optional<PipelineRunStep> findByRunIdAndOrderIndex(Long runId, int orderIndex);
    Optional<PipelineRunStep> findByRunIdAndPipelineStepId(Long runId, Long pipelineStepId);
}
