package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.dto.PipelineRunSummary;
import ru.iuribabalin.memorymcp.entity.Pipeline;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import ru.iuribabalin.memorymcp.entity.PipelineStepRoute;
import ru.iuribabalin.memorymcp.repository.PipelineRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRunRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRunStepRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRouteRepository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PipelineRunService {

    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineRunStepRepository pipelineRunStepRepository;
    private final PipelineRepository pipelineRepository;
    private final PipelineStepRepository pipelineStepRepository;
    private final PipelineStepRouteRepository pipelineStepRouteRepository;

    public PipelineRunService(PipelineRunRepository pipelineRunRepository,
                               PipelineRunStepRepository pipelineRunStepRepository,
                               PipelineRepository pipelineRepository,
                               PipelineStepRepository pipelineStepRepository,
                               PipelineStepRouteRepository pipelineStepRouteRepository) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineRunStepRepository = pipelineRunStepRepository;
        this.pipelineRepository = pipelineRepository;
        this.pipelineStepRepository = pipelineStepRepository;
        this.pipelineStepRouteRepository = pipelineStepRouteRepository;
    }

    @Transactional
    public PipelineRunDetail start(String slug, String parametersJson, String startedBy) {
        Pipeline pipeline = pipelineRepository.findBySlug(slug)
                .orElseThrow(() -> new PipelineNotFoundException(slug));
        List<PipelineStep> steps = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId());
        Instant now = Instant.now();
        PipelineRun run = new PipelineRun();
        run.setPipelineId(pipeline.getId());
        run.setStatus(PipelineRun.Status.RUNNING);
        run.setParametersJson(parametersJson);
        run.setStartedAt(now);
        run.setStartedBy(startedBy);
        run.setCurrentStepOrderIndex(resolveRootOrderIndex(steps));
        run = pipelineRunRepository.save(run);
        for (PipelineStep step : steps) {
            PipelineRunStep runStep = new PipelineRunStep();
            runStep.setRunId(run.getId());
            runStep.setPipelineStepId(step.getId());
            runStep.setOrderIndex(step.getOrderIndex());
            runStep.setTitle(step.getTitle());
            runStep.setContentType(step.getContentType());
            runStep.setStatus(PipelineRunStep.Status.PENDING);
            pipelineRunStepRepository.save(runStep);
        }
        return toDetail(run, pipeline.getSlug());
    }

    @Transactional
    public PipelineRunDetail updateStep(Long runId, int orderIndex, PipelineRunStep.Status status, String note, String outcome) {
        PipelineRun run = resolve(runId);
        PipelineRunStep runStep = pipelineRunStepRepository.findByRunIdAndOrderIndex(runId, orderIndex)
                .orElseThrow(() -> new PipelineRunStepNotFoundException(runId, orderIndex));
        Instant now = Instant.now();
        if (runStep.getStartedAt() == null && status == PipelineRunStep.Status.RUNNING) {
            runStep.setStartedAt(now);
        }
        if (status == PipelineRunStep.Status.DONE || status == PipelineRunStep.Status.FAILED
                || status == PipelineRunStep.Status.SKIPPED) {
            runStep.setFinishedAt(now);
        }
        runStep.setStatus(status);
        runStep.setNote(note);
        pipelineRunStepRepository.save(runStep);

        if (status == PipelineRunStep.Status.DONE && runStep.getPipelineStepId() != null) {
            run.setCurrentStepOrderIndex(resolveNextOrderIndex(run.getPipelineId(), runStep.getPipelineStepId(), orderIndex, outcome));
            pipelineRunRepository.save(run);
        }
        return toDetail(run, pipelineSlugOf(run));
    }

    @Transactional
    public PipelineRunDetail complete(Long runId, PipelineRun.Status status) {
        PipelineRun run = resolve(runId);
        run.setStatus(status);
        run.setFinishedAt(Instant.now());
        pipelineRunRepository.save(run);
        return toDetail(run, pipelineSlugOf(run));
    }

    @Transactional(readOnly = true)
    public PipelineRunDetail get(Long runId) {
        PipelineRun run = resolve(runId);
        return toDetail(run, pipelineSlugOf(run));
    }

    @Transactional(readOnly = true)
    public List<PipelineRunSummary> listByPipeline(String slug) {
        Pipeline pipeline = pipelineRepository.findBySlug(slug)
                .orElseThrow(() -> new PipelineNotFoundException(slug));
        return pipelineRunRepository.findByPipelineIdOrderByStartedAtDesc(pipeline.getId()).stream()
                .map(run -> toSummary(run, pipeline.getSlug()))
                .toList();
    }

    private PipelineRun resolve(Long runId) {
        return pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new PipelineRunNotFoundException(runId));
    }

    private String pipelineSlugOf(PipelineRun run) {
        return pipelineRepository.findById(run.getPipelineId()).map(Pipeline::getSlug).orElse(null);
    }

    private Integer resolveRootOrderIndex(List<PipelineStep> steps) {
        if (steps.isEmpty()) {
            return null;
        }
        List<PipelineStepRoute> allRoutes = pipelineStepRouteRepository.findByStepIdIn(
                steps.stream().map(PipelineStep::getId).toList());
        if (allRoutes.isEmpty()) {
            return steps.stream().mapToInt(PipelineStep::getOrderIndex).min().orElseThrow();
        }
        Set<Long> targeted = allRoutes.stream().map(PipelineStepRoute::getTargetStepId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> withOutgoing = allRoutes.stream().map(PipelineStepRoute::getStepId).collect(Collectors.toSet());
        return steps.stream()
                .filter(s -> !targeted.contains(s.getId()))
                .filter(s -> withOutgoing.contains(s.getId()))
                .mapToInt(PipelineStep::getOrderIndex)
                .min()
                .orElseThrow(() -> new IllegalStateException("Pipeline has no starting step"));
    }

    /**
     * A step with no routes falls back to legacy orderIndex+1 chaining only when the WHOLE
     * pipeline has no routes anywhere (a pipeline never touched by branching). Inside a pipeline
     * that does use branching, a step with no explicit routes is a dead end (end of that path) -
     * never an implicit chain to whatever step happens to sit at orderIndex+1.
     */
    private Integer resolveNextOrderIndex(Long pipelineId, Long finishedStepId, int finishedOrderIndex, String outcome) {
        List<PipelineStep> allSteps = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipelineId);
        List<PipelineStepRoute> allRoutes = pipelineStepRouteRepository.findByStepIdIn(
                allSteps.stream().map(PipelineStep::getId).toList());
        if (allRoutes.isEmpty()) {
            return allSteps.stream()
                    .map(PipelineStep::getOrderIndex)
                    .filter(i -> i == finishedOrderIndex + 1)
                    .findFirst()
                    .orElse(null);
        }
        List<PipelineStepRoute> stepRoutes = allRoutes.stream()
                .filter(r -> r.getStepId().equals(finishedStepId))
                .toList();
        if (stepRoutes.isEmpty()) {
            return null;
        }
        Optional<PipelineStepRoute> matched = stepRoutes.stream()
                .filter(r -> r.getOutcomeKey() != null && r.getOutcomeKey().equals(outcome))
                .findFirst();
        if (matched.isEmpty()) {
            matched = stepRoutes.stream().filter(r -> r.getOutcomeKey() == null).findFirst();
        }
        if (matched.isEmpty()) {
            List<String> validOutcomes = stepRoutes.stream()
                    .map(PipelineStepRoute::getOutcomeKey)
                    .filter(Objects::nonNull)
                    .toList();
            throw new PipelineRunInvalidOutcomeException(outcome, validOutcomes);
        }
        Long targetStepId = matched.get().getTargetStepId();
        if (targetStepId == null) {
            return null;
        }
        return allSteps.stream()
                .filter(s -> s.getId().equals(targetStepId))
                .map(PipelineStep::getOrderIndex)
                .findFirst()
                .orElse(null);
    }

    private PipelineRunSummary toSummary(PipelineRun run, String pipelineSlug) {
        return new PipelineRunSummary(run.getId(), run.getPipelineId(), pipelineSlug, run.getStatus(),
                run.getStartedAt(), run.getFinishedAt(), run.getStartedBy());
    }

    private PipelineRunDetail toDetail(PipelineRun run, String pipelineSlug) {
        List<PipelineRunDetail.PipelineRunStepView> steps = pipelineRunStepRepository
                .findByRunIdOrderByOrderIndexAsc(run.getId()).stream()
                .map(s -> new PipelineRunDetail.PipelineRunStepView(s.getId(), s.getOrderIndex(), s.getTitle(),
                        s.getContentType(), s.getStatus(), s.getNote(), s.getStartedAt(), s.getFinishedAt()))
                .toList();
        return new PipelineRunDetail(run.getId(), run.getPipelineId(), pipelineSlug, run.getStatus(),
                run.getParametersJson(), run.getStartedAt(), run.getFinishedAt(), run.getStartedBy(),
                run.getCurrentStepOrderIndex(), steps);
    }
}
