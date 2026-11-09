package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineVariable;

import java.util.List;

public interface PipelineVariableRepository extends JpaRepository<PipelineVariable, Long> {
    List<PipelineVariable> findByPipelineIdOrderByOrderIndexAsc(Long pipelineId);
    void deleteByPipelineId(Long pipelineId);
}
