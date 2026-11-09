package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineDataLink;

import java.util.List;

public interface PipelineDataLinkRepository extends JpaRepository<PipelineDataLink, Long> {
    List<PipelineDataLink> findBySourceStepIdIn(List<Long> stepIds);
    List<PipelineDataLink> findByTargetStepIdIn(List<Long> targetStepIds);
    /**
     * Links whose target is a variable rather than a step - how one variable's value is assembled
     * out of a parameter or another variable (V24).
     */
    List<PipelineDataLink> findByTargetVariableIdIn(List<Long> targetVariableIds);
    /**
     * Covers every link pointing at a step, whatever its source (step output, parameter, variable).
     * Links targeting a VARIABLE need {@link #deleteByTargetVariableIdIn} as well.
     */
    void deleteByTargetStepIdIn(List<Long> targetStepIds);
    void deleteByTargetVariableIdIn(List<Long> targetVariableIds);
}
