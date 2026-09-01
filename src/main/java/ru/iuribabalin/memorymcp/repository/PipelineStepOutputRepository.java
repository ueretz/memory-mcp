package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineStepOutput;

import java.util.List;

public interface PipelineStepOutputRepository extends JpaRepository<PipelineStepOutput, Long> {
    List<PipelineStepOutput> findByStepId(Long stepId);
    List<PipelineStepOutput> findByStepIdIn(List<Long> stepIds);
    void deleteByStepIdIn(List<Long> stepIds);
}
