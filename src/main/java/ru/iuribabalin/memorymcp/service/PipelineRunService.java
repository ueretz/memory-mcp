package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.dto.PipelineRunSummary;
import ru.iuribabalin.memorymcp.entity.Pipeline;
import ru.iuribabalin.memorymcp.entity.PipelineDataLink;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.PipelineRunStepOutput;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import ru.iuribabalin.memorymcp.entity.PipelineStepOutput;
import ru.iuribabalin.memorymcp.entity.PipelineStepRoute;
import ru.iuribabalin.memorymcp.repository.PipelineDataLinkRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRunRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRunStepOutputRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRunStepRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepOutputRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRouteRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final PipelineStepOutputRepository pipelineStepOutputRepository;
    private final PipelineDataLinkRepository pipelineDataLinkRepository;
    private final PipelineRunStepOutputRepository pipelineRunStepOutputRepository;
    private final ObjectMapper objectMapper;

    public PipelineRunService(PipelineRunRepository pipelineRunRepository,
                               PipelineRunStepRepository pipelineRunStepRepository,
                               PipelineRepository pipelineRepository,
                               PipelineStepRepository pipelineStepRepository,
                               PipelineStepRouteRepository pipelineStepRouteRepository,
                               PipelineStepOutputRepository pipelineStepOutputRepository,
                               PipelineDataLinkRepository pipelineDataLinkRepository,
                               PipelineRunStepOutputRepository pipelineRunStepOutputRepository,
                               ObjectMapper objectMapper) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineRunStepRepository = pipelineRunStepRepository;
        this.pipelineRepository = pipelineRepository;
        this.pipelineStepRepository = pipelineStepRepository;
        this.pipelineStepRouteRepository = pipelineStepRouteRepository;
        this.pipelineStepOutputRepository = pipelineStepOutputRepository;
        this.pipelineDataLinkRepository = pipelineDataLinkRepository;
        this.pipelineRunStepOutputRepository = pipelineRunStepOutputRepository;
        this.objectMapper = objectMapper;
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
    public PipelineRunDetail updateStep(Long runId, int orderIndex, PipelineRunStep.Status status, String note,
                                         String outcome, String outputsJson) {
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

        if (outputsJson != null && !outputsJson.isBlank() && runStep.getPipelineStepId() != null) {
            recordOutputs(runStep, outputsJson);
        }

        if ((status == PipelineRunStep.Status.DONE || status == PipelineRunStep.Status.SKIPPED)
                && runStep.getPipelineStepId() != null) {
            run.setCurrentStepOrderIndex(resolveNextOrderIndexForStatus(run.getPipelineId(), runStep.getPipelineStepId(), orderIndex, outcome, status));
            pipelineRunRepository.save(run);
        }
        return toDetail(run, pipelineSlugOf(run));
    }

    private void recordOutputs(PipelineRunStep runStep, String outputsJson) {
        List<PipelineStepOutput> declared = pipelineStepOutputRepository.findByStepId(runStep.getPipelineStepId());
        Map<String, Long> outputIdByName = declared.stream()
                .collect(Collectors.toMap(PipelineStepOutput::getName, PipelineStepOutput::getId));
        JsonNode node;
        try {
            node = objectMapper.readTree(outputsJson);
        } catch (Exception ex) {
            throw new PipelineRunUnknownOutputException(outputsJson,
                    declared.stream().map(PipelineStepOutput::getName).toList());
        }
        for (String name : node.propertyNames()) {
            Long outputId = outputIdByName.get(name);
            if (outputId == null) {
                throw new PipelineRunUnknownOutputException(name,
                        declared.stream().map(PipelineStepOutput::getName).toList());
            }
            PipelineRunStepOutput runStepOutput = pipelineRunStepOutputRepository
                    .findByRunStepIdAndOutputId(runStep.getId(), outputId)
                    .orElseGet(PipelineRunStepOutput::new);
            runStepOutput.setRunStepId(runStep.getId());
            runStepOutput.setOutputId(outputId);
            runStepOutput.setValue(node.get(name).asString());
            pipelineRunStepOutputRepository.save(runStepOutput);
        }
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
     * SKIPPED reuses DONE's route-resolution logic, but a skipped step was never actually
     * completed, so there is no genuine outcome to route on - we pass {@code null} and can only
     * follow a default route (or the legacy orderIndex+1 fallback) if one exists. Unlike DONE, an
     * unresolvable outcome must not surface as an error to the caller: the user explicitly asked
     * to skip this step, so "no way to know where to go" just ends that path here instead of
     * throwing PipelineRunInvalidOutcomeException back through the MCP tool.
     */
    private Integer resolveNextOrderIndexForStatus(Long pipelineId, Long finishedStepId, int finishedOrderIndex,
                                                    String outcome, PipelineRunStep.Status status) {
        if (status == PipelineRunStep.Status.SKIPPED) {
            try {
                return resolveNextOrderIndex(pipelineId, finishedStepId, finishedOrderIndex, null);
            } catch (PipelineRunInvalidOutcomeException ex) {
                return null;
            }
        }
        return resolveNextOrderIndex(pipelineId, finishedStepId, finishedOrderIndex, outcome);
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
        List<PipelineRunStep> runSteps = pipelineRunStepRepository.findByRunIdOrderByOrderIndexAsc(run.getId());
        List<Long> pipelineStepIds = runSteps.stream().map(PipelineRunStep::getPipelineStepId)
                .filter(Objects::nonNull).toList();
        Map<Long, PipelineStep> stepById = pipelineStepRepository.findAllById(pipelineStepIds).stream()
                .collect(Collectors.toMap(PipelineStep::getId, s -> s));
        Map<Long, Long> runStepIdByPipelineStepId = runSteps.stream()
                .filter(rs -> rs.getPipelineStepId() != null)
                .collect(Collectors.toMap(PipelineRunStep::getPipelineStepId, PipelineRunStep::getId));
        List<PipelineDataLink> incomingLinks = pipelineDataLinkRepository.findByTargetStepIdIn(pipelineStepIds);
        List<Long> sourceRunStepIds = incomingLinks.stream()
                .map(link -> runStepIdByPipelineStepId.get(link.getSourceStepId()))
                .filter(Objects::nonNull)
                .toList();
        Map<String, String> reportedValues = pipelineRunStepOutputRepository.findByRunStepIdIn(sourceRunStepIds).stream()
                .collect(Collectors.toMap(o -> o.getRunStepId() + ":" + o.getOutputId(), PipelineRunStepOutput::getValue));

        List<PipelineRunDetail.PipelineRunStepView> steps = runSteps.stream()
                .map(s -> new PipelineRunDetail.PipelineRunStepView(s.getId(), s.getOrderIndex(), s.getTitle(),
                        s.getContentType(), s.getStatus(), s.getNote(), s.getStartedAt(), s.getFinishedAt(),
                        resolveInstructionText(s, stepById, incomingLinks, runStepIdByPipelineStepId, reportedValues)))
                .toList();
        return new PipelineRunDetail(run.getId(), run.getPipelineId(), pipelineSlug, run.getStatus(),
                run.getParametersJson(), run.getStartedAt(), run.getFinishedAt(), run.getStartedBy(),
                run.getCurrentStepOrderIndex(), steps);
    }

    private String resolveInstructionText(PipelineRunStep runStep, Map<Long, PipelineStep> stepById,
                                           List<PipelineDataLink> allIncomingLinks,
                                           Map<Long, Long> runStepIdByPipelineStepId,
                                           Map<String, String> reportedValues) {
        if (runStep.getPipelineStepId() == null) {
            return null;
        }
        PipelineStep step = stepById.get(runStep.getPipelineStepId());
        if (step == null || step.getPromptText() == null) {
            return null;
        }
        String text = step.getPromptText();
        for (PipelineDataLink link : allIncomingLinks) {
            if (!link.getTargetStepId().equals(runStep.getPipelineStepId())) {
                continue;
            }
            Long sourceRunStepId = runStepIdByPipelineStepId.get(link.getSourceStepId());
            String value = sourceRunStepId != null
                    ? reportedValues.getOrDefault(sourceRunStepId + ":" + link.getSourceOutputId(), "")
                    : "";
            text = text.replace("{{data:" + link.getToken() + "}}", value);
        }
        return text;
    }
}
