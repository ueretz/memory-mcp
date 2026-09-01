package ru.iuribabalin.memorymcp.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.entity.Pipeline;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PipelineRunStepRepositoryTest {

    @Autowired
    private PipelineRepository pipelineRepository;
    @Autowired
    private PipelineStepRepository pipelineStepRepository;
    @Autowired
    private PipelineRunRepository pipelineRunRepository;
    @Autowired
    private PipelineRunStepRepository pipelineRunStepRepository;

    @Test
    void savesAndReadsBackConditionFieldsAndFindsByPipelineStepId() {
        Pipeline pipeline = new Pipeline();
        pipeline.setSlug("condition-repo-test");
        pipeline.setName("Condition repo test");
        pipeline.setCreatedAt(Instant.now());
        pipeline.setUpdatedAt(Instant.now());
        pipeline = pipelineRepository.save(pipeline);

        PipelineStep step = new PipelineStep();
        step.setPipelineId(pipeline.getId());
        step.setOrderIndex(0);
        step.setTitle("Check score");
        step.setContentType(PipelineStep.ContentType.CONDITION);
        step.setConditionOperator(PipelineStep.ConditionOperator.GREATER_THAN);
        step.setConditionValue("10");
        step = pipelineStepRepository.save(step);

        assertThat(step.getContentType()).isEqualTo(PipelineStep.ContentType.CONDITION);
        assertThat(step.getConditionOperator()).isEqualTo(PipelineStep.ConditionOperator.GREATER_THAN);
        assertThat(step.getConditionValue()).isEqualTo("10");

        PipelineRun run = new PipelineRun();
        run.setPipelineId(pipeline.getId());
        run.setStatus(PipelineRun.Status.RUNNING);
        run.setStartedAt(Instant.now());
        run = pipelineRunRepository.save(run);

        PipelineRunStep runStep = new PipelineRunStep();
        runStep.setRunId(run.getId());
        runStep.setPipelineStepId(step.getId());
        runStep.setOrderIndex(0);
        runStep.setTitle(step.getTitle());
        runStep.setContentType(step.getContentType());
        runStep.setStatus(PipelineRunStep.Status.PENDING);
        runStep = pipelineRunStepRepository.save(runStep);

        assertThat(pipelineRunStepRepository.findByRunIdAndPipelineStepId(run.getId(), step.getId()))
                .contains(runStep);
        assertThat(pipelineRunStepRepository.findByRunIdAndPipelineStepId(run.getId(), -1L)).isEmpty();
    }
}
