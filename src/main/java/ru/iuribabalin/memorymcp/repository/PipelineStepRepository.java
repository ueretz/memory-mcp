package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.util.List;

public interface PipelineStepRepository extends JpaRepository<PipelineStep, Long> {
    List<PipelineStep> findByPipelineIdOrderByOrderIndexAsc(Long pipelineId);
    void deleteByPipelineId(Long pipelineId);
}
