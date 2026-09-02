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
import ru.iuribabalin.memorymcp.repository.PipelineParameterRepository;
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
import java.util.HashMap;
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
    private final PipelineParameterRepository pipelineParameterRepository;
    private final ObjectMapper objectMapper;

    public PipelineRunService(PipelineRunRepository pipelineRunRepository,
                               PipelineRunStepRepository pipelineRunStepRepository,
                               PipelineRepository pipelineRepository,
                               PipelineStepRepository pipelineStepRepository,
                               PipelineStepRouteRepository pipelineStepRouteRepository,
                               PipelineStepOutputRepository pipelineStepOutputRepository,
                               PipelineDataLinkRepository pipelineDataLinkRepository,
                               PipelineRunStepOutputRepository pipelineRunStepOutputRepository,
                               PipelineParameterRepository pipelineParameterRepository,
                               ObjectMapper objectMapper) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineRunStepRepository = pipelineRunStepRepository;
        this.pipelineRepository = pipelineRepository;
        this.pipelineStepRepository = pipelineStepRepository;
        this.pipelineStepRouteRepository = pipelineStepRouteRepository;
        this.pipelineStepOutputRepository = pipelineStepOutputRepository;
        this.pipelineDataLinkRepository = pipelineDataLinkRepository;
        this.pipelineRunStepOutputRepository = pipelineRunStepOutputRepository;
        this.pipelineParameterRepository = pipelineParameterRepository;
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
        advancePastNonInteractiveSteps(run, steps);
        pipelineRunRepository.save(run);
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
            List<PipelineStep> allSteps = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(run.getPipelineId());
            advancePastNonInteractiveSteps(run, allSteps);
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

    private void advancePastNonInteractiveSteps(PipelineRun run, List<PipelineStep> orderedSteps) {
        while (run.getCurrentStepOrderIndex() != null) {
            PipelineStep step = orderedSteps.get(run.getCurrentStepOrderIndex());
            if (step.getContentType() == PipelineStep.ContentType.CONDITION) {
                run.setCurrentStepOrderIndex(executeConditionStep(run, step));
            } else if (step.getContentType() == PipelineStep.ContentType.VARIABLE) {
                run.setCurrentStepOrderIndex(executeVariableStep(run, step));
            } else {
                return;
            }
        }
    }

    private Integer executeConditionStep(PipelineRun run, PipelineStep step) {
        PipelineRunStep runStep = pipelineRunStepRepository.findByRunIdAndOrderIndex(run.getId(), step.getOrderIndex())
                .orElseThrow(() -> new PipelineRunStepNotFoundException(run.getId(), step.getOrderIndex()));
        String actualValue = resolveConditionInputValue(run, step);
        boolean result = evaluateCondition(step.getConditionOperator(), actualValue, step.getConditionValue());
        String outcome = result ? "true" : "false";
        Instant now = Instant.now();
        runStep.setStatus(PipelineRunStep.Status.DONE);
        runStep.setStartedAt(now);
        runStep.setFinishedAt(now);
        runStep.setNote("Condition evaluated to " + outcome + " (" + actualValue + " " + step.getConditionOperator() + " " + step.getConditionValue() + ")");
        pipelineRunStepRepository.save(runStep);
        return resolveNextOrderIndex(run.getPipelineId(), step.getId(), step.getOrderIndex(), outcome);
    }

    private String resolveConditionInputValue(PipelineRun run, PipelineStep step) {
        List<PipelineDataLink> incoming = pipelineDataLinkRepository.findByTargetStepIdIn(List.of(step.getId()));
        if (incoming.isEmpty()) {
            return "";
        }
        PipelineDataLink link = incoming.get(0);
        if (link.isParameterSourced()) {
            return resolveParameterValue(run, link.getSourceParameterId(), parameterValues(run));
        }
        return pipelineRunStepRepository.findByRunIdAndPipelineStepId(run.getId(), link.getSourceStepId())
                .flatMap(sourceRunStep -> pipelineRunStepOutputRepository.findByRunStepIdAndOutputId(sourceRunStep.getId(), link.getSourceOutputId()))
                .map(PipelineRunStepOutput::getValue)
                .orElse("");
    }

    /** The values the run was started with, keyed by parameter name (empty when none were given). */
    private Map<String, String> parameterValues(PipelineRun run) {
        Map<String, String> values = new HashMap<>();
        if (run.getParametersJson() == null || run.getParametersJson().isBlank()) {
            return values;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(run.getParametersJson());
        } catch (Exception ex) {
            return values;
        }
        for (String name : node.propertyNames()) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                values.put(name, value.asString());
            }
        }
        return values;
    }

    /** Given value, else the parameter's default, else "" - the same soft fallback unreported outputs get. */
    private String resolveParameterValue(PipelineRun run, Long parameterId, Map<String, String> given) {
        return pipelineParameterRepository.findById(parameterId)
                .map(parameter -> {
                    String value = given.get(parameter.getName());
                    if (value != null) {
                        return value;
                    }
                    return parameter.getDefaultValue() != null ? parameter.getDefaultValue() : "";
                })
                .orElse("");
    }

    private boolean evaluateCondition(PipelineStep.ConditionOperator operator, String actualValue, String comparand) {
        if (operator == PipelineStep.ConditionOperator.EQUALS) {
            return actualValue.equals(comparand);
        }
        Double actualNumber = parseNumberOrNull(actualValue);
        Double comparandNumber = parseNumberOrNull(comparand);
        if (actualNumber == null || comparandNumber == null) {
            return false;
        }
        return switch (operator) {
            case GREATER_THAN -> actualNumber > comparandNumber;
            case LESS_THAN -> actualNumber < comparandNumber;
            case GREATER_OR_EQUAL -> actualNumber >= comparandNumber;
            case LESS_OR_EQUAL -> actualNumber <= comparandNumber;
            default -> false;
        };
    }

    private Double parseNumberOrNull(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer executeVariableStep(PipelineRun run, PipelineStep step) {
        PipelineRunStep runStep = pipelineRunStepRepository.findByRunIdAndOrderIndex(run.getId(), step.getOrderIndex())
                .orElseThrow(() -> new PipelineRunStepNotFoundException(run.getId(), step.getOrderIndex()));
        Instant now = Instant.now();
        runStep.setStatus(PipelineRunStep.Status.DONE);
        runStep.setStartedAt(now);
        runStep.setFinishedAt(now);
        runStep.setNote("Variable set to its configured value");
        pipelineRunStepRepository.save(runStep);

        List<PipelineStepOutput> outputs = pipelineStepOutputRepository.findByStepId(step.getId());
        if (!outputs.isEmpty()) {
            PipelineStepOutput output = outputs.get(0);
            PipelineRunStepOutput runStepOutput = pipelineRunStepOutputRepository
                    .findByRunStepIdAndOutputId(runStep.getId(), output.getId())
                    .orElseGet(PipelineRunStepOutput::new);
            runStepOutput.setRunStepId(runStep.getId());
            runStepOutput.setOutputId(output.getId());
            runStepOutput.setValue(step.getPromptText());
            pipelineRunStepOutputRepository.save(runStepOutput);
        }
        return resolveNextOrderIndex(run.getPipelineId(), step.getId(), step.getOrderIndex(), null);
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
                .filter(link -> !link.isParameterSourced())
                .map(link -> runStepIdByPipelineStepId.get(link.getSourceStepId()))
                .filter(Objects::nonNull)
                .toList();
        Map<String, String> reportedValues = pipelineRunStepOutputRepository.findByRunStepIdIn(sourceRunStepIds).stream()
                .collect(Collectors.toMap(o -> o.getRunStepId() + ":" + o.getOutputId(), PipelineRunStepOutput::getValue));
        Map<String, String> givenParameters = parameterValues(run);
        Map<String, String> parameterValueByToken = new HashMap<>();
        for (PipelineDataLink link : incomingLinks) {
            if (link.isParameterSourced()) {
                parameterValueByToken.put(link.getToken(), resolveParameterValue(run, link.getSourceParameterId(), givenParameters));
            }
        }

        List<PipelineRunDetail.PipelineRunStepView> steps = runSteps.stream()
                .map(s -> new PipelineRunDetail.PipelineRunStepView(s.getId(), s.getOrderIndex(), s.getTitle(),
                        s.getContentType(), s.getStatus(), s.getNote(), s.getStartedAt(), s.getFinishedAt(),
                        resolveInstructionText(s, stepById, incomingLinks, runStepIdByPipelineStepId, reportedValues, parameterValueByToken)))
                .toList();
        return new PipelineRunDetail(run.getId(), run.getPipelineId(), pipelineSlug, run.getStatus(),
                run.getParametersJson(), run.getStartedAt(), run.getFinishedAt(), run.getStartedBy(),
                run.getCurrentStepOrderIndex(), steps);
    }

    private String resolveInstructionText(PipelineRunStep runStep, Map<Long, PipelineStep> stepById,
                                           List<PipelineDataLink> allIncomingLinks,
                                           Map<Long, Long> runStepIdByPipelineStepId,
                                           Map<String, String> reportedValues,
                                           Map<String, String> parameterValueByToken) {
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
            String value;
            if (link.isParameterSourced()) {
                value = parameterValueByToken.getOrDefault(link.getToken(), "");
            } else {
                Long sourceRunStepId = runStepIdByPipelineStepId.get(link.getSourceStepId());
                value = sourceRunStepId != null
                        ? reportedValues.getOrDefault(sourceRunStepId + ":" + link.getSourceOutputId(), "")
                        : "";
            }
            text = text.replace("{{data:" + link.getToken() + "}}", value);
        }
        return text;
    }
}
