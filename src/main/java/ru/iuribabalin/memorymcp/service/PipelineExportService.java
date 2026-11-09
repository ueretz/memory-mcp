package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineDetail;
import ru.iuribabalin.memorymcp.dto.PipelineExportEnvelope;
import ru.iuribabalin.memorymcp.dto.PipelineImportResponse;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.repository.PipelineRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PipelineExportService {

    private static final int FORMAT_VERSION = 1;

    private final PipelineService pipelineService;
    private final PipelineRepository pipelineRepository;

    public PipelineExportService(PipelineService pipelineService, PipelineRepository pipelineRepository) {
        this.pipelineService = pipelineService;
        this.pipelineRepository = pipelineRepository;
    }

    @Transactional(readOnly = true)
    public PipelineExportEnvelope export(String slug) {
        PipelineDetail detail = pipelineService.get(slug);
        Map<Integer, Integer> stepIndexByOrderIndex = new HashMap<>();
        for (int i = 0; i < detail.steps().size(); i++) {
            stepIndexByOrderIndex.put(detail.steps().get(i).orderIndex(), i);
        }
        List<String> warnings = new ArrayList<>();
        List<PipelineUpsertRequest.StepRequest> steps = detail.steps().stream()
                .map(step -> toPortableStep(step, stepIndexByOrderIndex, warnings))
                .toList();
        PipelineUpsertRequest pipeline = new PipelineUpsertRequest(
                detail.slug(), detail.name(), detail.description(), detail.projectScope(),
                detail.parameters().stream()
                        .map(parameter -> new PipelineUpsertRequest.ParameterRequest(
                                parameter.name(), parameter.label(), parameter.type(),
                                parameter.required(), parameter.defaultValue()))
                        .toList(),
                steps,
                detail.parameterLinks().stream()
                        .map(link -> new PipelineUpsertRequest.ParameterLinkRequest(
                                link.token(), link.parameterName(),
                                link.targetStepOrderIndex() == null ? null : stepIndexByOrderIndex.get(link.targetStepOrderIndex()),
                                link.targetVariableName()))
                        .toList(),
                detail.variables().stream()
                        .map(variable -> new PipelineUpsertRequest.VariableRequest(variable.name(), variable.value()))
                        .toList(),
                detail.variableLinks().stream()
                        .map(link -> new PipelineUpsertRequest.VariableLinkRequest(
                                link.token(), link.variableName(),
                                link.targetStepOrderIndex() == null ? null : stepIndexByOrderIndex.get(link.targetStepOrderIndex()),
                                link.targetVariableName()))
                        .toList(),
                detail.startStepOrderIndex() == null ? null : stepIndexByOrderIndex.get(detail.startStepOrderIndex()));
        return new PipelineExportEnvelope(FORMAT_VERSION, pipeline, warnings);
    }

    @Transactional
    public PipelineImportResponse importPipeline(PipelineExportEnvelope envelope, String slugOverride) {
        if (envelope.formatVersion() != FORMAT_VERSION) {
            throw new PipelineUnsupportedFormatVersionException(envelope.formatVersion());
        }
        PipelineUpsertRequest source = envelope.pipeline();
        List<String> warnings = new ArrayList<>(envelope.warnings());
        // Tokens have a database-wide unique index, not a pipeline-scoped one. Fresh tokens let a
        // pipeline coexist with its source; prompt placeholders must move to the same new tokens.
        Map<String, String> tokenReplacements = new HashMap<>();
        source.steps().forEach(step -> step.dataLinksOut().forEach(
                link -> tokenReplacements.put(link.token(), UUID.randomUUID().toString())));
        source.parameterLinks().forEach(
                link -> tokenReplacements.put(link.token(), UUID.randomUUID().toString()));
        source.variableLinks().forEach(
                link -> tokenReplacements.put(link.token(), UUID.randomUUID().toString()));
        List<PipelineUpsertRequest.StepRequest> steps = source.steps().stream()
                .map(step -> toImportedStep(step, warnings, tokenReplacements))
                .toList();
        List<PipelineUpsertRequest.ParameterLinkRequest> parameterLinks = source.parameterLinks().stream()
                .map(link -> new PipelineUpsertRequest.ParameterLinkRequest(
                        tokenReplacements.get(link.token()), link.parameterName(),
                        link.targetStepIndex(), link.targetVariableName()))
                .toList();
        List<PipelineUpsertRequest.VariableLinkRequest> variableLinks = source.variableLinks().stream()
                .map(link -> new PipelineUpsertRequest.VariableLinkRequest(
                        tokenReplacements.get(link.token()), link.variableName(),
                        link.targetStepIndex(), link.targetVariableName()))
                .toList();
        // A variable's own value can hold a placeholder too - that is how one variable is built out
        // of a parameter or another variable - so it goes through the same token rewrite as prompts.
        List<PipelineUpsertRequest.VariableRequest> variables = source.variables().stream()
                .map(variable -> new PipelineUpsertRequest.VariableRequest(
                        variable.name(), retoken(variable.value(), tokenReplacements)))
                .toList();
        String requestedSlug = slugOverride != null ? slugOverride : source.slug();
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                firstFreeSlug(requestedSlug), source.name(), source.description(), source.projectScope(),
                source.parameters(), steps, parameterLinks, variables, variableLinks, source.startStepIndex());
        PipelineDetail created = pipelineService.createImported(request, null);
        return new PipelineImportResponse(created, warnings);
    }

    private PipelineUpsertRequest.StepRequest toPortableStep(
            PipelineDetail.PipelineStepView step, Map<Integer, Integer> stepIndexByOrderIndex,
            List<String> warnings) {
        addAssetWarning(step.title(), step.assetId(), step.referenceAssetId(), warnings);
        return new PipelineUpsertRequest.StepRequest(
                step.title(), step.contentType(), step.promptText(), null, null,
                step.positionX(), step.positionY(),
                step.routes().stream()
                        .map(route -> new PipelineUpsertRequest.StepRequest.RouteRequest(
                                route.outcomeKey(), stepIndexByOrderIndex.get(route.targetStepOrderIndex())))
                        .toList(),
                step.outputs().stream()
                        .map(output -> new PipelineUpsertRequest.StepRequest.OutputRequest(output.name()))
                        .toList(),
                step.dataLinksOut().stream()
                        .map(link -> new PipelineUpsertRequest.StepRequest.DataLinkRequest(
                                link.token(), link.sourceOutputName(),
                                stepIndexByOrderIndex.get(link.targetStepOrderIndex())))
                        .toList(),
                step.conditionOperator(), step.conditionValue(), step.conditionMode(), step.maxIterations(),
                step.watchSource(), step.watchAspect(), step.checkIntervalSeconds(), step.watchTimeoutSeconds(),
                stepIndexByOrderIndex.get(step.regionStartStepOrderIndex()), step.delegationHint());
    }

    private PipelineUpsertRequest.StepRequest toImportedStep(
            PipelineUpsertRequest.StepRequest step, List<String> warnings,
            Map<String, String> tokenReplacements) {
        addAssetWarning(step.title(), step.assetId(), step.referenceAssetId(), warnings);
        String promptText = retoken(step.promptText(), tokenReplacements);
        return new PipelineUpsertRequest.StepRequest(
                step.title(), step.contentType(), promptText, null, null,
                step.positionX(), step.positionY(), step.routes(), step.outputs(),
                step.dataLinksOut().stream()
                        .map(link -> new PipelineUpsertRequest.StepRequest.DataLinkRequest(
                                tokenReplacements.get(link.token()), link.sourceOutputName(), link.targetStepIndex()))
                        .toList(),
                step.conditionOperator(), step.conditionValue(), step.conditionMode(), step.maxIterations(),
                step.watchSource(), step.watchAspect(), step.checkIntervalSeconds(), step.watchTimeoutSeconds(),
                step.regionStartStepIndex(), step.delegationHint());
    }

    /** Moves every {@code {{data:token}}} placeholder in a text onto the freshly minted token. */
    private String retoken(String text, Map<String, String> tokenReplacements) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (Map.Entry<String, String> replacement : tokenReplacements.entrySet()) {
            result = result.replace(
                    "{{data:" + replacement.getKey() + "}}", "{{data:" + replacement.getValue() + "}}");
        }
        return result;
    }

    private void addAssetWarning(String stepTitle, Long assetId, Long referenceAssetId, List<String> warnings) {
        if (assetId == null && referenceAssetId == null) {
            return;
        }
        String warning = "Step '" + stepTitle
                + "' lost its asset references; asset files are not included in pipeline exports.";
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private String firstFreeSlug(String requestedSlug) {
        if (pipelineRepository.findBySlug(requestedSlug).isEmpty()) {
            return requestedSlug;
        }
        int suffix = 2;
        while (pipelineRepository.findBySlug(requestedSlug + "-" + suffix).isPresent()) {
            suffix++;
        }
        return requestedSlug + "-" + suffix;
    }
}
