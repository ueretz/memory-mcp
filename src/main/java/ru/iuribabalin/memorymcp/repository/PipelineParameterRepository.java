package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineParameter;

import java.util.List;

public interface PipelineParameterRepository extends JpaRepository<PipelineParameter, Long> {
    List<PipelineParameter> findByPipelineIdOrderByOrderIndexAsc(Long pipelineId);
    void deleteByPipelineId(Long pipelineId);
}
