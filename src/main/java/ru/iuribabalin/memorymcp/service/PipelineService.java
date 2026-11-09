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
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import ru.iuribabalin.memorymcp.entity.PipelineStepOutput;
import ru.iuribabalin.memorymcp.entity.PipelineStepRoute;
import ru.iuribabalin.memorymcp.repository.PipelineDataLinkRepository;
import ru.iuribabalin.memorymcp.repository.PipelineParameterRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRunRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepOutputRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRouteRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PipelineService {

    /** A GITLAB_MR watcher's target must name a merge request; the project and iid come from it. */
    private static final Pattern MERGE_REQUEST_URL = Pattern.compile("/merge_requests/\\d+");

    /** {@code {{data:token}}} / {@code {{param}}} - a target whose real value only exists at run time. */
    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{\\{[^}]+}}");

    private final PipelineRepository pipelineRepository;
    private final PipelineParameterRepository pipelineParameterRepository;
    private final PipelineStepRepository pipelineStepRepository;
    private final PipelineStepRouteRepository pipelineStepRouteRepository;
    private final PipelineStepOutputRepository pipelineStepOutputRepository;
    private final PipelineDataLinkRepository pipelineDataLinkRepository;
    private final PipelineAssetService pipelineAssetService;
    private final PipelineRunRepository pipelineRunRepository;
    private final ObjectMapper objectMapper;

    public PipelineService(PipelineRepository pipelineRepository,
                            PipelineParameterRepository pipelineParameterRepository,
                            PipelineStepRepository pipelineStepRepository,
                            PipelineStepRouteRepository pipelineStepRouteRepository,
                            PipelineStepOutputRepository pipelineStepOutputRepository,
                            PipelineDataLinkRepository pipelineDataLinkRepository,
                            PipelineAssetService pipelineAssetService,
                           PipelineRunRepository pipelineRunRepository,
                            ObjectMapper objectMapper) {
        this.pipelineRepository = pipelineRepository;
        this.pipelineParameterRepository = pipelineParameterRepository;
        this.pipelineStepRepository = pipelineStepRepository;
        this.pipelineStepRouteRepository = pipelineStepRouteRepository;
        this.pipelineStepOutputRepository = pipelineStepOutputRepository;
        this.pipelineDataLinkRepository = pipelineDataLinkRepository;
        this.pipelineAssetService = pipelineAssetService;
        this.pipelineRunRepository = pipelineRunRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    /**
     * Pipelines are shared across projects: every pipeline is listed regardless of the project the
     * caller is working in. {@code projectScope} is accepted for backward compatibility with older
     * callers and only records where a pipeline was authored - it never filters.
     */
    public List<PipelineSummary> list(String projectScope) {
        return pipelineRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public PipelineDetail get(String slug) {
        return toDetail(resolve(slug));
    }

    @Transactional
    public PipelineDetail create(PipelineUpsertRequest request, String createdBy) {
        return create(request, createdBy, false);
    }

    @Transactional
    public PipelineDetail createImported(PipelineUpsertRequest request, String createdBy) {
        // Asset-less MD_FILE steps remain importable so the author can reattach the file on the
        // target installation; ordinary saves must still reject that incomplete step.
        return create(request, createdBy, true);
    }

    private PipelineDetail create(PipelineUpsertRequest request, String createdBy, boolean imported) {
        if (!imported) {
            validateSteps(request.steps());
        }
        validateGraph(request.steps());
        validateDataLinks(request.steps());
        validateParameterLinks(request);
        validateStepKinds(request.steps(), request.parameterLinks());
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
        validateParameterLinks(request);
        validateStepKinds(request.steps(), request.parameterLinks());
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
                    pipelineDataLinkRepository.deleteByTargetStepIdIn(stepIds);
                    pipelineParameterRepository.deleteByPipelineId(pipeline.getId());
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
                        pipelineStepOutputRepository.findByStepIdOrderByIdAsc(step.getId()).stream()
                                .map(PipelineStepOutput::getName)
                                .toList(),
                        step.getContentType(),
                        step.getWatchSource(), step.getWatchAspect(),
                        step.getCheckIntervalSeconds(), step.getWatchTimeoutSeconds()))
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
            // A PARALLEL step's routes are all unkeyed on purpose - every one of them fires.
            if (defaultRoutes > 1 && steps.get(i).contentType() != PipelineStep.ContentType.PARALLEL) {
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
        int[] entryInDegree = inDegree.clone();
        for (int i = 0; i < n; i++) {
            for (int target : adjacency.get(i)) {
                if (steps.get(target).contentType() == PipelineStep.ContentType.LOOP
                        && reachableFrom(target, adjacency).contains(i)) {
                    entryInDegree[target]--;
                }
            }
        }
        List<String> roots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean isolated = inDegree[i] == 0 && outDegree[i] == 0;
            if (!isolated && entryInDegree[i] == 0) {
                roots.add(steps.get(i).title());
            }
        }
        if (roots.size() != 1) {
            throw new PipelineInvalidGraphException(roots.isEmpty()
                    ? "Pipeline has no starting step — every step has an incoming route"
                    : "Pipeline has more than one starting step: " + String.join(", ", roots));
        }
        // Cycles are legal only when they return to a LOOP step. Removing every incoming LOOP edge
        // must therefore leave an acyclic graph; the loop itself enforces a finite iteration limit.
        int[] remaining = new int[n];
        for (int i = 0; i < n; i++) {
            for (int next : adjacency.get(i)) {
                if (steps.get(next).contentType() != PipelineStep.ContentType.LOOP) {
                    remaining[next]++;
                }
            }
        }
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
                if (steps.get(next).contentType() != PipelineStep.ContentType.LOOP && --remaining[next] == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited < n) {
            throw new PipelineInvalidGraphException("Pipeline has a cycle that does not return to a LOOP step");
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

    /**
     * Parameter links: the parameter must exist in this same request, the target must be a real
     * step, and tokens are unique across BOTH kinds of link (they share one unique index and one
     * {@code {{data:token}}} namespace inside prompts).
     */
    private void validateParameterLinks(PipelineUpsertRequest request) {
        Set<String> parameterNames = request.parameters().stream()
                .map(PipelineUpsertRequest.ParameterRequest::name)
                .collect(Collectors.toSet());
        Set<String> tokens = new HashSet<>();
        for (PipelineUpsertRequest.StepRequest step : request.steps()) {
            for (PipelineUpsertRequest.StepRequest.DataLinkRequest link : step.dataLinksOut()) {
                if (!tokens.add(link.token())) {
                    throw new PipelineInvalidGraphException("Data link token '" + link.token() + "' is used more than once");
                }
            }
        }
        int n = request.steps().size();
        for (PipelineUpsertRequest.ParameterLinkRequest link : request.parameterLinks()) {
            if (!parameterNames.contains(link.parameterName())) {
                throw new PipelineInvalidGraphException(
                        "Parameter link wires a parameter '" + link.parameterName() + "' the pipeline does not declare");
            }
            Integer target = link.targetStepIndex();
            if (target == null || target < 0 || target >= n) {
                throw new PipelineInvalidGraphException(
                        "Parameter '" + link.parameterName() + "' is wired to step index " + target
                                + ", but the pipeline only has " + n + " steps");
            }
            if (link.token() == null || link.token().isBlank() || !tokens.add(link.token())) {
                throw new PipelineInvalidGraphException("Data link token '" + link.token() + "' is used more than once");
            }
        }
    }

    private void validateStepKinds(List<PipelineUpsertRequest.StepRequest> steps,
                                   List<PipelineUpsertRequest.ParameterLinkRequest> parameterLinks) {
        int n = steps.size();
        int[] incomingDataLinkCount = new int[n];
        // Counted apart from parameter links: a COMMAND watcher may take a value the USER typed at
        // run start, but never one a model produced on an earlier step (validateWatcherStep).
        int[] incomingStepDataLinkCount = new int[n];
        for (PipelineUpsertRequest.StepRequest step : steps) {
            for (PipelineUpsertRequest.StepRequest.DataLinkRequest link : step.dataLinksOut()) {
                if (link.targetStepIndex() != null) {
                    incomingDataLinkCount[link.targetStepIndex()]++;
                    incomingStepDataLinkCount[link.targetStepIndex()]++;
                }
            }
        }
        for (PipelineUpsertRequest.ParameterLinkRequest link : parameterLinks) {
            if (link.targetStepIndex() != null && link.targetStepIndex() >= 0 && link.targetStepIndex() < n) {
                incomingDataLinkCount[link.targetStepIndex()]++;
            }
        }
        for (int i = 0; i < n; i++) {
            PipelineUpsertRequest.StepRequest step = steps.get(i);
            if (step.contentType() == PipelineStep.ContentType.CONDITION) {
                validateConditionStep(step, incomingDataLinkCount[i]);
            } else if (step.contentType() == PipelineStep.ContentType.VARIABLE) {
                validateVariableStep(step);
            } else if (step.contentType() == PipelineStep.ContentType.PARALLEL) {
                validateParallelStep(step, incomingDataLinkCount[i]);
            } else if (step.contentType() == PipelineStep.ContentType.JOIN) {
                validateJoinStep(step, incomingDataLinkCount[i]);
            } else if (step.contentType() == PipelineStep.ContentType.LOOP) {
                validateLoopStep(step, incomingDataLinkCount[i]);
            } else if (step.contentType() == PipelineStep.ContentType.WATCHER) {
                validateWatcherStep(step, incomingStepDataLinkCount[i]);
            }
        }
        validateNoWatcherInsideAParallelFanOut(steps);
    }

    /**
     * A watcher waits by holding one background poller owned by the leading agent, while a branch of
     * an open fan-out is worked by a sub-agent that must not call pipeline tools at all - so a
     * watcher can never live inside a fan-out. It may perfectly well sit AFTER the branches have
     * merged, though: past the JOIN the run is back to a single active step worked by the leading
     * agent, which is why the walk below stops at JOIN steps instead of running to the end.
     */
    private void validateNoWatcherInsideAParallelFanOut(List<PipelineUpsertRequest.StepRequest> steps) {
        List<List<Integer>> adjacency = buildExecutionAdjacency(steps);
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).contentType() != PipelineStep.ContentType.PARALLEL) {
                continue;
            }
            for (int inFanOut : reachableBeforeAnyJoin(i, adjacency, steps)) {
                if (steps.get(inFanOut).contentType() == PipelineStep.ContentType.WATCHER) {
                    throw new PipelineInvalidGraphException(
                            "Step '" + steps.get(inFanOut).title() + "' is type WATCHER and cannot sit inside the "
                                    + "fan-out of the PARALLEL step '" + steps.get(i).title()
                                    + "' - a watcher waits in the leading agent's session, not in a parallel branch. "
                                    + "Move it after the JOIN that merges the branches.");
                }
            }
        }
    }

    /** Steps reachable from {@code start} without passing through a JOIN - i.e. the open fan-out. */
    private Set<Integer> reachableBeforeAnyJoin(int start, List<List<Integer>> adjacency,
                                                 List<PipelineUpsertRequest.StepRequest> steps) {
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            int current = stack.pop();
            for (int next : adjacency.get(current)) {
                if (steps.get(next).contentType() == PipelineStep.ContentType.JOIN) {
                    continue;
                }
                if (visited.add(next)) {
                    stack.push(next);
                }
            }
        }
        return visited;
    }

    /** The outcome keys a watcher's routes must cover - fixed by its source and aspect. */
    static List<String> watcherOutcomeKeys(PipelineStep.WatchSource source, PipelineStep.WatchAspect aspect) {
        if (source == PipelineStep.WatchSource.COMMAND) {
            return List.of("success", "timeout");
        }
        return aspect == PipelineStep.WatchAspect.REVIEW
                ? List.of("approved", "comments", "timeout")
                : List.of("success", "failed", "timeout");
    }

    private void validateWatcherStep(PipelineUpsertRequest.StepRequest step, int incomingDataLinkCount) {
        if (step.watchSource() == null) {
            throw new PipelineInvalidParametersException(
                    "Step '" + step.title() + "' is type WATCHER and needs a watch source (GITLAB_MR or COMMAND)");
        }
        boolean gitlab = step.watchSource() == PipelineStep.WatchSource.GITLAB_MR;
        if (gitlab == (step.watchAspect() == null)) {
            throw new PipelineInvalidParametersException(
                    "Step '" + step.title() + "' is type WATCHER: a GITLAB_MR watcher needs an aspect "
                            + "(PIPELINE or REVIEW) and a COMMAND watcher must not have one");
        }
        if (step.promptText() == null || step.promptText().isBlank()) {
            throw new PipelineInvalidParametersException(
                    "Step '" + step.title() + "' is type WATCHER but has no watch target"
                            + (gitlab ? " - give it a merge request URL" : " - give it a probe command"));
        }
        // Only a literal target can be checked for shape. The headline composition feeds the URL in
        // from the step that opened the MR ({{data:...}}), and a parameter token is filled in at run
        // start - neither looks like a URL at save time, and rejecting them would block the feature's
        // main use.
        boolean templatedTarget = TEMPLATE_TOKEN.matcher(step.promptText()).find();
        if (gitlab && !templatedTarget && !MERGE_REQUEST_URL.matcher(step.promptText()).find()) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is a GITLAB_MR watcher, so its target must be a merge request URL "
                            + "containing '/merge_requests/<number>', or a token that resolves to one");
        }
        Integer interval = step.checkIntervalSeconds();
        if (interval == null || interval < 10 || interval > 3600) {
            throw new PipelineInvalidParametersException(
                    "Step '" + step.title() + "' is type WATCHER and needs a check interval between 10 and 3600 seconds");
        }
        Integer timeout = step.watchTimeoutSeconds();
        if (timeout == null || timeout < 60 || timeout > 86400) {
            throw new PipelineInvalidParametersException(
                    "Step '" + step.title() + "' is type WATCHER and needs a timeout between 60 and 86400 seconds");
        }
        if (timeout < interval) {
            throw new PipelineInvalidParametersException(
                    "Step '" + step.title() + "' is type WATCHER: предельное время ожидания должно быть "
                            + "не меньше интервала проверки");
        }
        if (!gitlab && incomingDataLinkCount > 0) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is a COMMAND watcher and cannot take an incoming data link - a "
                            + "value produced by an earlier step must never land in the position of a command this "
                            + "watcher re-runs on every check");
        }
        List<String> expected = watcherOutcomeKeys(step.watchSource(), step.watchAspect());
        List<String> actual = step.routes().stream()
                .map(PipelineUpsertRequest.StepRequest.RouteRequest::outcomeKey)
                .sorted(Comparator.nullsFirst(Comparator.naturalOrder()))
                .toList();
        if (!actual.equals(expected.stream().sorted().toList())) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type WATCHER and must have exactly these outcome routes: "
                            + String.join(", ", expected));
        }
    }

    private void validateLoopStep(PipelineUpsertRequest.StepRequest step, int incomingDataLinkCount) {
        if (step.maxIterations() == null || step.maxIterations() < 1 || step.maxIterations() > 100) {
            throw new PipelineInvalidParametersException(
                    "Step '" + step.title() + "' is type LOOP and needs a max iteration count between 1 and 100");
        }
        long defaults = step.routes().stream().filter(r -> r.outcomeKey() == null).count();
        long done = step.routes().stream().filter(r -> "done".equals(r.outcomeKey())).count();
        if (step.routes().size() != 2 || defaults != 1 || done != 1) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type LOOP and needs one body route and one 'done' route");
        }
        if (step.routes().stream().noneMatch(r -> r.outcomeKey() == null && r.targetStepIndex() != null)) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type LOOP and needs its body route wired to a step");
        }
        if (!step.outputs().isEmpty() || incomingDataLinkCount != 0) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type LOOP and carries no data - it can have neither outputs nor incoming data links");
        }
    }

    private void validateParallelStep(PipelineUpsertRequest.StepRequest step, int incomingDataLinkCount) {
        long wired = step.routes().stream().filter(r -> r.targetStepIndex() != null).count();
        if (wired < 1) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type PARALLEL and needs at least one branch wired to a step");
        }
        if (step.routes().stream().anyMatch(r -> r.outcomeKey() != null)) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type PARALLEL - its branches cannot have outcome keys, every branch always runs");
        }
        if (!step.outputs().isEmpty() || incomingDataLinkCount != 0) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type PARALLEL and carries no data - it can have neither outputs nor incoming data links");
        }
    }

    private void validateJoinStep(PipelineUpsertRequest.StepRequest step, int incomingDataLinkCount) {
        boolean hasNamedRoute = step.routes().stream().anyMatch(r -> r.outcomeKey() != null);
        if (step.routes().size() > 1 || hasNamedRoute) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type JOIN and can have at most one route, with no outcome key");
        }
        if (!step.outputs().isEmpty() || incomingDataLinkCount != 0) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type JOIN and carries no data - it can have neither outputs nor incoming data links");
        }
    }

    private void validateConditionStep(PipelineUpsertRequest.StepRequest step, int incomingDataLinkCount) {
        if (step.conditionMode() == PipelineStep.ConditionMode.PROMPT) {
            if (step.promptText() == null || step.promptText().isBlank()) {
                throw new PipelineInvalidParametersException(
                        "Step '" + step.title() + "' is a PROMPT condition but has no yes/no question");
            }
        } else {
            if (step.conditionOperator() == null || step.conditionValue() == null || step.conditionValue().isBlank()) {
                throw new PipelineInvalidParametersException(
                        "Step '" + step.title() + "' is type CONDITION but is missing a comparison operator/value");
            }
            if (incomingDataLinkCount != 1) {
                throw new PipelineInvalidGraphException(
                        "Step '" + step.title() + "' is a VALUE condition and must have exactly one incoming data link (has "
                                + incomingDataLinkCount + ")");
            }
        }
        Set<String> outcomeKeys = step.routes().stream()
                .map(PipelineUpsertRequest.StepRequest.RouteRequest::outcomeKey)
                .collect(Collectors.toSet());
        if (step.routes().size() != 2 || !outcomeKeys.equals(Set.of("true", "false"))) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type CONDITION and must have exactly two routes with outcome keys 'true' and 'false'");
        }
    }

    private void validateVariableStep(PipelineUpsertRequest.StepRequest step) {
        if (step.promptText() == null || step.promptText().isBlank()) {
            throw new PipelineInvalidParametersException(
                    "Step '" + step.title() + "' is type VARIABLE but has no value");
        }
        if (step.outputs().size() != 1) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type VARIABLE and must declare exactly one output (has "
                            + step.outputs().size() + ")");
        }
        boolean hasNamedRoute = step.routes().stream().anyMatch(r -> r.outcomeKey() != null);
        if (step.routes().size() > 1 || hasNamedRoute) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type VARIABLE and can have at most one route, with no outcome key");
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
        List<Long> existingStepIds = stepIdsOf(pipelineId);
        // Links first: parameter-sourced links reference parameters, which are replaced next.
        pipelineDataLinkRepository.deleteByTargetStepIdIn(existingStepIds);
        pipelineParameterRepository.deleteByPipelineId(pipelineId);
        Map<String, Long> parameterIdByName = new HashMap<>();
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
            parameterIdByName.put(parameter.getName(), pipelineParameterRepository.save(parameter).getId());
        }

        pipelineStepOutputRepository.deleteByStepIdIn(existingStepIds);
        pipelineStepRouteRepository.deleteByStepIdIn(existingStepIds);
        pipelineStepRepository.deleteByPipelineId(pipelineId);
        // The derived deleteBy* queries remove entities through the persistence context, and
        // Hibernate flushes INSERTs before DELETEs. Without an explicit flush the re-created data
        // links (same client-generated token as the rows being removed) would hit
        // ux_pipeline_data_links_token while the old rows still exist.
        pipelineStepRepository.flush();

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
            step.setConditionOperator(stepRequest.conditionOperator());
            step.setConditionValue(stepRequest.conditionValue());
            step.setConditionMode(stepRequest.conditionMode());
            step.setMaxIterations(stepRequest.maxIterations());
            step.setWatchSource(stepRequest.watchSource());
            step.setWatchAspect(stepRequest.watchAspect());
            step.setCheckIntervalSeconds(stepRequest.checkIntervalSeconds());
            step.setWatchTimeoutSeconds(stepRequest.watchTimeoutSeconds());
            step.setDelegationHint(stepRequest.delegationHint());
            step.setPositionX(stepRequest.positionX());
            step.setPositionY(stepRequest.positionY());
            savedSteps.add(pipelineStepRepository.save(step));
        }
        // Second pass: a region pointer names a step by index, and the id it resolves to only
        // exists once every step of this save has a row - same reason routes are wired below.
        for (int i = 0; i < request.steps().size(); i++) {
            Integer regionStart = request.steps().get(i).regionStartStepIndex();
            if (regionStart != null) {
                savedSteps.get(i).setRegionStartStepId(savedSteps.get(regionStart).getId());
                pipelineStepRepository.save(savedSteps.get(i));
            }
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
        for (PipelineUpsertRequest.ParameterLinkRequest linkRequest : request.parameterLinks()) {
            PipelineDataLink link = new PipelineDataLink();
            link.setToken(linkRequest.token());
            link.setSourceParameterId(parameterIdByName.get(linkRequest.parameterName()));
            link.setTargetStepId(savedSteps.get(linkRequest.targetStepIndex()).getId());
            pipelineDataLinkRepository.save(link);
        }
    }

    private PipelineSummary toSummary(Pipeline pipeline) {
        int parameterCount = pipelineParameterRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).size();
        int stepCount = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).size();
        PipelineRun lastRun = pipelineRunRepository.findFirstByPipelineIdOrderByStartedAtDesc(pipeline.getId()).orElse(null);
        return new PipelineSummary(pipeline.getId(), pipeline.getSlug(), pipeline.getName(), pipeline.getDescription(),
                pipeline.getProjectScope(), parameterCount, stepCount, pipeline.getCreatedBy(), pipeline.getUpdatedAt(),
                lastRun == null ? null : lastRun.getId(),
                lastRun == null ? null : lastRun.getStatus(),
                lastRun == null ? null : lastRun.getStartedAt());
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
                    List<PipelineStepOutput> stepOutputs = pipelineStepOutputRepository.findByStepIdOrderByIdAsc(s.getId());
                    Map<Long, PipelineStepOutput> outputsById = stepOutputs.stream()
                            .collect(Collectors.toMap(PipelineStepOutput::getId, o -> o));
                    return new PipelineDetail.PipelineStepView(
                            s.getId(), s.getOrderIndex(), s.getTitle(), s.getContentType(), s.getPromptText(),
                            s.getAssetId(), s.getReferenceAssetId(), s.getPositionX(), s.getPositionY(),
                            pipelineStepRouteRepository.findByStepId(s.getId()).stream()
                                    .map(r -> new PipelineDetail.PipelineStepView.RouteView(
                                            r.getOutcomeKey(),
                                            r.getTargetStepId() != null ? orderIndexById.get(r.getTargetStepId()) : null))
                                    .toList(),
                            stepOutputs.stream()
                                    .map(o -> new PipelineDetail.PipelineStepView.OutputView(o.getId(), o.getName()))
                                    .toList(),
                            pipelineDataLinkRepository.findBySourceStepIdIn(List.of(s.getId())).stream()
                                    .map(link -> new PipelineDetail.PipelineStepView.DataLinkView(
                                            link.getId(), link.getToken(), outputsById.get(link.getSourceOutputId()).getName(),
                                            orderIndexById.get(link.getTargetStepId()), titleById.get(link.getTargetStepId())))
                                    .toList(),
                            s.getConditionOperator(), s.getConditionValue(), s.getConditionMode(), s.getMaxIterations(),
                            s.getWatchSource(), s.getWatchAspect(), s.getCheckIntervalSeconds(),
                            s.getWatchTimeoutSeconds(),
                            s.getRegionStartStepId() != null ? orderIndexById.get(s.getRegionStartStepId()) : null,
                            s.getDelegationHint());
                })
                .toList();
        Map<Long, String> parameterNameById = parameters.stream()
                .collect(Collectors.toMap(PipelineDetail.PipelineParameterView::id, PipelineDetail.PipelineParameterView::name));
        List<PipelineDetail.PipelineParameterLinkView> parameterLinks = pipelineDataLinkRepository
                .findByTargetStepIdIn(pipelineSteps.stream().map(PipelineStep::getId).toList()).stream()
                .filter(PipelineDataLink::isParameterSourced)
                .map(link -> new PipelineDetail.PipelineParameterLinkView(
                        link.getId(), link.getToken(), parameterNameById.get(link.getSourceParameterId()),
                        orderIndexById.get(link.getTargetStepId()), titleById.get(link.getTargetStepId())))
                .toList();
        return new PipelineDetail(pipeline.getId(), pipeline.getSlug(), pipeline.getName(), pipeline.getDescription(),
                pipeline.getProjectScope(), parameters, steps, parameterLinks, pipeline.getCreatedBy(), pipeline.getCreatedAt(), pipeline.getUpdatedAt());
    }
}
