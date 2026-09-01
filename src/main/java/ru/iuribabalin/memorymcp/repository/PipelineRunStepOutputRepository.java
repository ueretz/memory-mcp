package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineRunStepOutput;

import java.util.List;
import java.util.Optional;

public interface PipelineRunStepOutputRepository extends JpaRepository<PipelineRunStepOutput, Long> {
    List<PipelineRunStepOutput> findByRunStepIdIn(List<Long> runStepIds);
    Optional<PipelineRunStepOutput> findByRunStepIdAndOutputId(Long runStepId, Long outputId);
}
