package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineDetail;
import ru.iuribabalin.memorymcp.dto.PipelineSummary;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.entity.Pipeline;
import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import ru.iuribabalin.memorymcp.repository.PipelineParameterRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRepository;

import java.time.Instant;
import java.util.List;

@Service
public class PipelineService {

    private final PipelineRepository pipelineRepository;
    private final PipelineParameterRepository pipelineParameterRepository;
    private final PipelineStepRepository pipelineStepRepository;

    public PipelineService(PipelineRepository pipelineRepository,
                            PipelineParameterRepository pipelineParameterRepository,
                            PipelineStepRepository pipelineStepRepository) {
        this.pipelineRepository = pipelineRepository;
        this.pipelineParameterRepository = pipelineParameterRepository;
        this.pipelineStepRepository = pipelineStepRepository;
    }

    @Transactional(readOnly = true)
    public List<PipelineSummary> list(String projectScope) {
        return pipelineRepository.findByProjectScopeOrderByUpdatedAtDesc(projectScope).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public PipelineDetail get(String slug) {
        return toDetail(resolve(slug));
    }

    @Transactional
    public PipelineDetail create(PipelineUpsertRequest request, String createdBy) {
        if (pipelineRepository.findBySlug(request.slug()).isPresent()) {
            throw new PipelineSlugTakenException(request.slug());
        }
        Instant now = Instant.now();
        Pipeline pipeline = new Pipeline();
        pipeline.setSlug(request.slug());
        pipeline.setCreatedBy(createdBy);
        pipeline.setCreatedAt(now);
        applyFields(pipeline, request, now);
        pipeline = pipelineRepository.save(pipeline);
        replaceParametersAndSteps(pipeline.getId(), request);
        return toDetail(resolve(request.slug()));
    }

    @Transactional
    public PipelineDetail update(String slug, PipelineUpsertRequest request) {
        Pipeline pipeline = resolve(slug);
        applyFields(pipeline, request, Instant.now());
        pipelineRepository.save(pipeline);
        replaceParametersAndSteps(pipeline.getId(), request);
        return toDetail(resolve(slug));
    }

    @Transactional
    public boolean delete(String slug) {
        return pipelineRepository.findBySlug(slug)
                .map(pipeline -> {
                    pipelineParameterRepository.deleteByPipelineId(pipeline.getId());
                    pipelineStepRepository.deleteByPipelineId(pipeline.getId());
                    pipelineRepository.delete(pipeline);
                    return true;
                })
                .orElse(false);
    }

    Pipeline resolve(String slug) {
        return pipelineRepository.findBySlug(slug)
                .orElseThrow(() -> new PipelineNotFoundException(slug));
    }

    private void applyFields(Pipeline pipeline, PipelineUpsertRequest request, Instant now) {
        pipeline.setName(request.name());
        pipeline.setDescription(request.description());
        pipeline.setProjectScope(request.projectScope());
        pipeline.setUpdatedAt(now);
    }

    private void replaceParametersAndSteps(Long pipelineId, PipelineUpsertRequest request) {
        pipelineParameterRepository.deleteByPipelineId(pipelineId);
        int paramIndex = 0;
        for (PipelineUpsertRequest.ParameterRequest parameterRequest : request.parameters()) {
            PipelineParameter parameter = new PipelineParameter();
            parameter.setPipelineId(pipelineId);
            parameter.setName(parameterRequest.name());
            parameter.setLabel(parameterRequest.label());
            parameter.setType(parameterRequest.type());
            parameter.setRequired(parameterRequest.required());
            parameter.setDefaultValue(parameterRequest.defaultValue());
            parameter.setOrderIndex(paramIndex++);
            pipelineParameterRepository.save(parameter);
        }
        pipelineStepRepository.deleteByPipelineId(pipelineId);
        int stepIndex = 0;
        for (PipelineUpsertRequest.StepRequest stepRequest : request.steps()) {
            PipelineStep step = new PipelineStep();
            step.setPipelineId(pipelineId);
            step.setOrderIndex(stepIndex++);
            step.setTitle(stepRequest.title());
            step.setContentType(stepRequest.contentType());
            step.setPromptText(stepRequest.promptText());
            step.setAssetId(stepRequest.assetId());
            step.setReferenceAssetId(stepRequest.referenceAssetId());
            pipelineStepRepository.save(step);
        }
    }

    private PipelineSummary toSummary(Pipeline pipeline) {
        int parameterCount = pipelineParameterRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).size();
        int stepCount = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).size();
        return new PipelineSummary(pipeline.getId(), pipeline.getSlug(), pipeline.getName(), pipeline.getDescription(),
                pipeline.getProjectScope(), parameterCount, stepCount, pipeline.getCreatedBy(), pipeline.getUpdatedAt());
    }

    private PipelineDetail toDetail(Pipeline pipeline) {
        List<PipelineDetail.PipelineParameterView> parameters = pipelineParameterRepository
                .findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).stream()
                .map(p -> new PipelineDetail.PipelineParameterView(p.getId(), p.getName(), p.getLabel(), p.getType(), p.isRequired(), p.getDefaultValue(), p.getOrderIndex()))
                .toList();
        List<PipelineDetail.PipelineStepView> steps = pipelineStepRepository
                .findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).stream()
                .map(s -> new PipelineDetail.PipelineStepView(s.getId(), s.getOrderIndex(), s.getTitle(), s.getContentType(), s.getPromptText(), s.getAssetId(), s.getReferenceAssetId()))
                .toList();
        return new PipelineDetail(pipeline.getId(), pipeline.getSlug(), pipeline.getName(), pipeline.getDescription(),
                pipeline.getProjectScope(), parameters, steps, pipeline.getCreatedBy(), pipeline.getCreatedAt(), pipeline.getUpdatedAt());
    }
}
