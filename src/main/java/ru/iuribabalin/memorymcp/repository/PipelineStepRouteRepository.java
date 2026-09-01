package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineStepRoute;

import java.util.List;

public interface PipelineStepRouteRepository extends JpaRepository<PipelineStepRoute, Long> {
    List<PipelineStepRoute> findByStepId(Long stepId);
    List<PipelineStepRoute> findByStepIdIn(List<Long> stepIds);
    void deleteByStepIdIn(List<Long> stepIds);
}
