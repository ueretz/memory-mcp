package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineDetail;
import ru.iuribabalin.memorymcp.dto.PipelineExecutionDetail;
import ru.iuribabalin.memorymcp.dto.PipelineSummary;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.entity.Pipeline;
import ru.iuribabalin.memorymcp.entity.PipelineDataLink;
import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import ru.iuribabalin.memorymcp.entity.PipelineStepOutput;
import ru.iuribabalin.memorymcp.entity.PipelineStepRoute;
import ru.iuribabalin.memorymcp.repository.PipelineDataLinkRepository;
import ru.iuribabalin.memorymcp.repository.PipelineParameterRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepOutputRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRouteRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PipelineService {

    private final PipelineRepository pipelineRepository;
    private final PipelineParameterRepository pipelineParameterRepository;
    private final PipelineStepRepository pipelineStepRepository;
    private final PipelineStepRouteRepository pipelineStepRouteRepository;
    private final PipelineStepOutputRepository pipelineStepOutputRepository;
    private final PipelineDataLinkRepository pipelineDataLinkRepository;
    private final PipelineAssetService pipelineAssetService;
    private final ObjectMapper objectMapper;

    public PipelineService(PipelineRepository pipelineRepository,
                            PipelineParameterRepository pipelineParameterRepository,
                            PipelineStepRepository pipelineStepRepository,
                            PipelineStepRouteRepository pipelineStepRouteRepository,
                            PipelineStepOutputRepository pipelineStepOutputRepository,
                            PipelineDataLinkRepository pipelineDataLinkRepository,
                            PipelineAssetService pipelineAssetService,
                            ObjectMapper objectMapper) {
        this.pipelineRepository = pipelineRepository;
        this.pipelineParameterRepository = pipelineParameterRepository;
        this.pipelineStepRepository = pipelineStepRepository;
        this.pipelineStepRouteRepository = pipelineStepRouteRepository;
        this.pipelineStepOutputRepository = pipelineStepOutputRepository;
        this.pipelineDataLinkRepository = pipelineDataLinkRepository;
        this.pipelineAssetService = pipelineAssetService;
        this.objectMapper = objectMapper;
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
        validateSteps(request.steps());
        validateGraph(request.steps());
        validateDataLinks(request.steps());
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
        validateSteps(request.steps());
        validateGraph(request.steps());
        validateDataLinks(request.steps());
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
                    List<Long> stepIds = stepIdsOf(pipeline.getId());
                    pipelineParameterRepository.deleteByPipelineId(pipeline.getId());
                    pipelineDataLinkRepository.deleteBySourceStepIdIn(stepIds);
                    pipelineStepOutputRepository.deleteByStepIdIn(stepIds);
                    pipelineStepRouteRepository.deleteByStepIdIn(stepIds);
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

    @Transactional(readOnly = true)
    public PipelineExecutionDetail getForExecution(String slug) {
        Pipeline pipeline = resolve(slug);
        List<PipelineExecutionDetail.ParameterView> parameters = pipelineParameterRepository
                .findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).stream()
                .map(p -> new PipelineExecutionDetail.ParameterView(p.getName(), p.getLabel(), p.getType(), p.isRequired(), p.getDefaultValue()))
                .toList();
        List<PipelineStep> steps = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId());
        Map<Long, PipelineStep> stepsById = steps.stream().collect(Collectors.toMap(PipelineStep::getId, s -> s));
        List<PipelineExecutionDetail.StepView> stepViews = steps.stream()
                .map(step -> new PipelineExecutionDetail.StepView(
                        step.getOrderIndex(),
                        step.getTitle(),
                        resolveInstructionText(step),
                        step.getReferenceAssetId() != null ? pipelineAssetService.readAsText(step.getReferenceAssetId()) : null,
                        pipelineStepRouteRepository.findByStepId(step.getId()).stream()
                                .map(r -> new PipelineExecutionDetail.StepView.RouteView(
                                        r.getOutcomeKey(),
                                        r.getTargetStepId() != null ? stepsById.get(r.getTargetStepId()).getOrderIndex() : null,
                                        r.getTargetStepId() != null ? stepsById.get(r.getTargetStepId()).getTitle() : null))
                                .toList(),
                        pipelineStepOutputRepository.findByStepId(step.getId()).stream()
                                .map(PipelineStepOutput::getName)
                                .toList()))
                .toList();
        return new PipelineExecutionDetail(pipeline.getSlug(), pipeline.getName(), pipeline.getDescription(), parameters, stepViews);
    }

    @Transactional(readOnly = true)
    public void validateParameters(String slug, String parametersJson) {
        Pipeline pipeline = resolve(slug);
        List<PipelineParameter> parameters = pipelineParameterRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId());
        Set<String> provided = new HashSet<>();
        if (parametersJson != null && !parametersJson.isBlank()) {
            JsonNode node;
            try {
                node = objectMapper.readTree(parametersJson);
            } catch (Exception ex) {
                throw new PipelineInvalidParametersException("parametersJson is not valid JSON: " + ex.getMessage());
            }
            provided.addAll(node.propertyNames());
        }
        List<String> missing = parameters.stream()
                .filter(PipelineParameter::isRequired)
                .map(PipelineParameter::getName)
                .filter(name -> !provided.contains(name))
                .toList();
        if (!missing.isEmpty()) {
            throw new PipelineInvalidParametersException("Missing required parameters: " + String.join(", ", missing));
        }
    }

    private void validateSteps(List<PipelineUpsertRequest.StepRequest> steps) {
        for (PipelineUpsertRequest.StepRequest step : steps) {
            if (step.contentType() == PipelineStep.ContentType.MD_FILE && step.assetId() == null) {
                throw new PipelineInvalidParametersException(
                        "Step '" + step.title() + "' is type MD_FILE but has no uploaded file — upload a .md file before saving");
            }
        }
    }

    /**
     * A step with zero routes falls back to legacy orderIndex+1 chaining, but only pipeline-wide:
     * if ANY step anywhere has an explicit route, every step's edges come ONLY from its own
     * explicit routes (an empty list means "dead end / not yet wired", never an implicit chain to
     * whatever a later-created step happens to occupy at orderIndex+1).
     */
    private void validateGraph(List<PipelineUpsertRequest.StepRequest> steps) {
        int n = steps.size();
        if (n == 0 || steps.stream().allMatch(s -> s.routes().isEmpty())) {
            return;
        }
        int[] inDegree = new int[n];
        int[] outDegree = new int[n];
        List<List<Integer>> adjacency = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adjacency.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            List<PipelineUpsertRequest.StepRequest.RouteRequest> routes = steps.get(i).routes();
            long defaultRoutes = routes.stream().filter(r -> r.outcomeKey() == null).count();
            if (defaultRoutes > 1) {
                throw new PipelineInvalidGraphException(
                        "Step '" + steps.get(i).title() + "' has more than one default route");
            }
            Set<String> seenOutcomeKeys = new HashSet<>();
            for (PipelineUpsertRequest.StepRequest.RouteRequest route : routes) {
                if (route.outcomeKey() != null && !seenOutcomeKeys.add(route.outcomeKey())) {
                    throw new PipelineInvalidGraphException(
                            "Step '" + steps.get(i).title() + "' has more than one route with outcome key '" + route.outcomeKey() + "'");
                }
            }
            for (PipelineUpsertRequest.StepRequest.RouteRequest route : routes) {
                outDegree[i]++;
                if (route.targetStepIndex() != null) {
                    int target = route.targetStepIndex();
                    if (target < 0 || target >= n) {
                        throw new PipelineInvalidGraphException(
                                "Step '" + steps.get(i).title() + "' routes to step index " + target
                                        + ", but the pipeline only has " + n + " steps");
                    }
                    inDegree[target]++;
                    adjacency.get(i).add(target);
                }
            }
        }
        List<String> roots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean isolated = inDegree[i] == 0 && outDegree[i] == 0;
            if (!isolated && inDegree[i] == 0) {
                roots.add(steps.get(i).title());
            }
        }
        if (roots.size() != 1) {
            throw new PipelineInvalidGraphException(roots.isEmpty()
                    ? "Pipeline has no starting step — every step has an incoming route"
                    : "Pipeline has more than one starting step: " + String.join(", ", roots));
        }
        int[] remaining = inDegree.clone();
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (remaining[i] == 0) {
                queue.add(i);
            }
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            int current = queue.poll();
            visited++;
            for (int next : adjacency.get(current)) {
                if (--remaining[next] == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited < n) {
            throw new PipelineInvalidGraphException("Pipeline has a cycle in its step routes");
        }
    }

    /**
     * Reachability for data links uses the same adjacency PipelineRunService executes with:
     * explicit routes if any step declares one, otherwise implicit orderIndex+1 chaining.
     */
    private List<List<Integer>> buildExecutionAdjacency(List<PipelineUpsertRequest.StepRequest> steps) {
        int n = steps.size();
        List<List<Integer>> adjacency = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adjacency.add(new ArrayList<>());
        }
        boolean anyRoutes = steps.stream().anyMatch(s -> !s.routes().isEmpty());
        if (!anyRoutes) {
            for (int i = 0; i < n - 1; i++) {
                adjacency.get(i).add(i + 1);
            }
            return adjacency;
        }
        for (int i = 0; i < n; i++) {
            for (PipelineUpsertRequest.StepRequest.RouteRequest route : steps.get(i).routes()) {
                if (route.targetStepIndex() != null) {
                    adjacency.get(i).add(route.targetStepIndex());
                }
            }
        }
        return adjacency;
    }

    private Set<Integer> reachableFrom(int start, List<List<Integer>> adjacency) {
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            int current = stack.pop();
            for (int next : adjacency.get(current)) {
                if (visited.add(next)) {
                    stack.push(next);
                }
            }
        }
        return visited;
    }

    private void validateDataLinks(List<PipelineUpsertRequest.StepRequest> steps) {
        int n = steps.size();
        List<List<Integer>> adjacency = buildExecutionAdjacency(steps);
        for (int i = 0; i < n; i++) {
            PipelineUpsertRequest.StepRequest step = steps.get(i);
            Set<String> outputNames = new HashSet<>();
            for (PipelineUpsertRequest.StepRequest.OutputRequest output : step.outputs()) {
                if (!outputNames.add(output.name())) {
                    throw new PipelineInvalidGraphException(
                            "Step '" + step.title() + "' declares more than one output named '" + output.name() + "'");
                }
            }
            for (PipelineUpsertRequest.StepRequest.DataLinkRequest link : step.dataLinksOut()) {
                if (!outputNames.contains(link.sourceOutputName())) {
                    throw new PipelineInvalidGraphException(
                            "Step '" + step.title() + "' wires an output '" + link.sourceOutputName() + "' it never declared");
                }
                Integer target = link.targetStepIndex();
                if (target == null || target < 0 || target >= n) {
                    throw new PipelineInvalidGraphException(
                            "Step '" + step.title() + "' wires a data link to step index " + target
                                    + ", but the pipeline only has " + n + " steps");
                }
                if (target == i) {
                    throw new PipelineInvalidGraphException(
                            "Step '" + step.title() + "' cannot wire a data link to itself");
                }
                if (!reachableFrom(i, adjacency).contains(target)) {
                    throw new PipelineInvalidGraphException(
                            "Step '" + step.title() + "' wires a data link to step '" + steps.get(target).title()
                                    + "', but that step can never run after it - a data link's source must be an ancestor of its target");
                }
            }
        }
    }

    private String resolveInstructionText(PipelineStep step) {
        if (step.getContentType() != PipelineStep.ContentType.MD_FILE) {
            return step.getPromptText();
        }
        if (step.getAssetId() == null) {
            throw new PipelineInvalidParametersException(
                    "Step '" + step.getTitle() + "' is type MD_FILE but has no uploaded file");
        }
        return pipelineAssetService.readAsText(step.getAssetId());
    }

    private void applyFields(Pipeline pipeline, PipelineUpsertRequest request, Instant now) {
        pipeline.setName(request.name());
        pipeline.setDescription(request.description());
        pipeline.setProjectScope(request.projectScope());
        pipeline.setUpdatedAt(now);
    }

    private List<Long> stepIdsOf(Long pipelineId) {
        return pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipelineId).stream()
                .map(PipelineStep::getId)
                .toList();
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

        List<Long> existingStepIds = stepIdsOf(pipelineId);
        pipelineDataLinkRepository.deleteBySourceStepIdIn(existingStepIds);
        pipelineStepOutputRepository.deleteByStepIdIn(existingStepIds);
        pipelineStepRouteRepository.deleteByStepIdIn(existingStepIds);
        pipelineStepRepository.deleteByPipelineId(pipelineId);

        List<PipelineStep> savedSteps = new ArrayList<>();
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
            step.setPositionX(stepRequest.positionX());
            step.setPositionY(stepRequest.positionY());
            savedSteps.add(pipelineStepRepository.save(step));
        }
        for (int i = 0; i < request.steps().size(); i++) {
            for (PipelineUpsertRequest.StepRequest.RouteRequest routeRequest : request.steps().get(i).routes()) {
                PipelineStepRoute route = new PipelineStepRoute();
                route.setStepId(savedSteps.get(i).getId());
                route.setOutcomeKey(routeRequest.outcomeKey());
                route.setTargetStepId(routeRequest.targetStepIndex() != null
                        ? savedSteps.get(routeRequest.targetStepIndex()).getId()
                        : null);
                pipelineStepRouteRepository.save(route);
            }
        }

        List<List<PipelineStepOutput>> outputsByStep = new ArrayList<>();
        for (int i = 0; i < request.steps().size(); i++) {
            List<PipelineStepOutput> stepOutputs = new ArrayList<>();
            for (PipelineUpsertRequest.StepRequest.OutputRequest outputRequest : request.steps().get(i).outputs()) {
                PipelineStepOutput output = new PipelineStepOutput();
                output.setStepId(savedSteps.get(i).getId());
                output.setName(outputRequest.name());
                stepOutputs.add(pipelineStepOutputRepository.save(output));
            }
            outputsByStep.add(stepOutputs);
        }
        for (int i = 0; i < request.steps().size(); i++) {
            Map<String, Long> outputIdByName = outputsByStep.get(i).stream()
                    .collect(Collectors.toMap(PipelineStepOutput::getName, PipelineStepOutput::getId));
            for (PipelineUpsertRequest.StepRequest.DataLinkRequest linkRequest : request.steps().get(i).dataLinksOut()) {
                PipelineDataLink link = new PipelineDataLink();
                link.setToken(linkRequest.token());
                link.setSourceStepId(savedSteps.get(i).getId());
                link.setSourceOutputId(outputIdByName.get(linkRequest.sourceOutputName()));
                link.setTargetStepId(savedSteps.get(linkRequest.targetStepIndex()).getId());
                pipelineDataLinkRepository.save(link);
            }
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
        List<PipelineStep> pipelineSteps = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId());
        Map<Long, Integer> orderIndexById = pipelineSteps.stream()
                .collect(Collectors.toMap(PipelineStep::getId, PipelineStep::getOrderIndex));
        Map<Long, String> titleById = pipelineSteps.stream()
                .collect(Collectors.toMap(PipelineStep::getId, PipelineStep::getTitle));
        List<PipelineDetail.PipelineStepView> steps = pipelineSteps.stream()
                .map(s -> {
                    Map<Long, PipelineStepOutput> outputsById = pipelineStepOutputRepository.findByStepId(s.getId()).stream()
                            .collect(Collectors.toMap(PipelineStepOutput::getId, o -> o));
                    return new PipelineDetail.PipelineStepView(
                            s.getId(), s.getOrderIndex(), s.getTitle(), s.getContentType(), s.getPromptText(),
                            s.getAssetId(), s.getReferenceAssetId(), s.getPositionX(), s.getPositionY(),
                            pipelineStepRouteRepository.findByStepId(s.getId()).stream()
                                    .map(r -> new PipelineDetail.PipelineStepView.RouteView(
                                            r.getOutcomeKey(),
                                            r.getTargetStepId() != null ? orderIndexById.get(r.getTargetStepId()) : null))
                                    .toList(),
                            outputsById.values().stream()
                                    .map(o -> new PipelineDetail.PipelineStepView.OutputView(o.getId(), o.getName()))
                                    .toList(),
                            pipelineDataLinkRepository.findBySourceStepIdIn(List.of(s.getId())).stream()
                                    .map(link -> new PipelineDetail.PipelineStepView.DataLinkView(
                                            link.getId(), link.getToken(), outputsById.get(link.getSourceOutputId()).getName(),
                                            orderIndexById.get(link.getTargetStepId()), titleById.get(link.getTargetStepId())))
                                    .toList());
                })
                .toList();
        return new PipelineDetail(pipeline.getId(), pipeline.getSlug(), pipeline.getName(), pipeline.getDescription(),
                pipeline.getProjectScope(), parameters, steps, pipeline.getCreatedBy(), pipeline.getCreatedAt(), pipeline.getUpdatedAt());
    }
}
