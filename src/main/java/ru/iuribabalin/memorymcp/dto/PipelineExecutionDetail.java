package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.util.List;

public record PipelineExecutionDetail(
        String slug, String name, String description,
        List<ParameterView> parameters, List<StepView> steps) {

    public record ParameterView(String name, String label, PipelineParameter.Type type, boolean required, String defaultValue) {
    }

    public record StepView(int orderIndex, String title, String instructionText, String referenceText,
                            List<RouteView> routes, List<String> outputs,
                            /**
                             * The kind of step, and - for a WATCHER - everything the agent needs to
                             * run its wait: what to watch, how often to probe, and how long to keep
                             * waiting before the run takes the {@code timeout} branch. Null on
                             * every other kind. The watch target itself is {@code instructionText}.
                             */
                            PipelineStep.ContentType contentType,
                            PipelineStep.WatchSource watchSource, PipelineStep.WatchAspect watchAspect,
                            Integer checkIntervalSeconds, Integer watchTimeoutSeconds) {

        /** Pre-V22 shape: no step kind and no watcher config. */
        public StepView(int orderIndex, String title, String instructionText, String referenceText,
                        List<RouteView> routes, List<String> outputs) {
            this(orderIndex, title, instructionText, referenceText, routes, outputs,
                    null, null, null, null, null);
        }

        public record RouteView(String outcomeKey, Integer targetStepOrderIndex, String targetStepTitle) {
        }
    }
}
