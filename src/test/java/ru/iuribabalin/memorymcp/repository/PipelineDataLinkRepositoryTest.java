package ru.iuribabalin.memorymcp.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.entity.Pipeline;
import ru.iuribabalin.memorymcp.entity.PipelineDataLink;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import ru.iuribabalin.memorymcp.entity.PipelineStepOutput;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PipelineDataLinkRepositoryTest {

    @Autowired
    private PipelineRepository pipelineRepository;
    @Autowired
    private PipelineStepRepository pipelineStepRepository;
    @Autowired
    private PipelineStepOutputRepository pipelineStepOutputRepository;
    @Autowired
    private PipelineDataLinkRepository pipelineDataLinkRepository;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void deletingTheSourceStepCascadesToItsOutputsAndDataLinks() {
        Pipeline pipeline = new Pipeline();
        pipeline.setSlug("data-link-repo-test");
        pipeline.setName("Data link repo test");
        pipeline.setCreatedAt(Instant.now());
        pipeline.setUpdatedAt(Instant.now());
        pipeline = pipelineRepository.save(pipeline);

        PipelineStep source = new PipelineStep();
        source.setPipelineId(pipeline.getId());
        source.setOrderIndex(0);
        source.setTitle("Source");
        source.setContentType(PipelineStep.ContentType.PROMPT);
        source.setPromptText("do the thing");
        source = pipelineStepRepository.save(source);

        PipelineStep target = new PipelineStep();
        target.setPipelineId(pipeline.getId());
        target.setOrderIndex(1);
        target.setTitle("Target");
        target.setContentType(PipelineStep.ContentType.PROMPT);
        target.setPromptText("use {{data:tok-1}}");
        target = pipelineStepRepository.save(target);

        PipelineStepOutput output = new PipelineStepOutput();
        output.setStepId(source.getId());
        output.setName("summary");
        output = pipelineStepOutputRepository.save(output);

        PipelineDataLink link = new PipelineDataLink();
        link.setToken("tok-1");
        link.setSourceStepId(source.getId());
        link.setSourceOutputId(output.getId());
        link.setTargetStepId(target.getId());
        link = pipelineDataLinkRepository.save(link);

        pipelineStepRepository.deleteById(source.getId());
        pipelineStepRepository.flush();
        entityManager.clear();

        assertThat(pipelineStepOutputRepository.findById(output.getId())).isEmpty();
        assertThat(pipelineDataLinkRepository.findById(link.getId())).isEmpty();
    }
}
