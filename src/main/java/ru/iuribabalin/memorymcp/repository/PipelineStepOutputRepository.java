package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineStepOutput;

import java.util.List;

public interface PipelineStepOutputRepository extends JpaRepository<PipelineStepOutput, Long> {
    /**
     * Declaration order, which is insertion order: the author's order of the output pins is what
     * the dashboard draws and what {@code executeVariableStep} means by "the first output". The
     * rows carry no order column, so the id sequence is that order - without ORDER BY Postgres is
     * free to hand them back in any order at all, and on a freshly migrated database it does.
     */
    List<PipelineStepOutput> findByStepIdOrderByIdAsc(Long stepId);
    List<PipelineStepOutput> findByStepIdIn(List<Long> stepIds);
    void deleteByStepIdIn(List<Long> stepIds);
}
