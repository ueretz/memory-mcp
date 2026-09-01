package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineDataLink;

import java.util.List;

public interface PipelineDataLinkRepository extends JpaRepository<PipelineDataLink, Long> {
    List<PipelineDataLink> findBySourceStepIdIn(List<Long> stepIds);
    List<PipelineDataLink> findByTargetStepIdIn(List<Long> targetStepIds);
    void deleteBySourceStepIdIn(List<Long> stepIds);
}
