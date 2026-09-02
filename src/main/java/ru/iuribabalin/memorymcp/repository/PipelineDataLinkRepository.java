package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineDataLink;

import java.util.List;

public interface PipelineDataLinkRepository extends JpaRepository<PipelineDataLink, Long> {
    List<PipelineDataLink> findBySourceStepIdIn(List<Long> stepIds);
    List<PipelineDataLink> findByTargetStepIdIn(List<Long> targetStepIds);
    /** Every link points at a step, so deleting by target covers step-sourced AND parameter-sourced links. */
    void deleteByTargetStepIdIn(List<Long> targetStepIds);
}
