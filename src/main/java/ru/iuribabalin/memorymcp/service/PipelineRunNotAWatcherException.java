package ru.iuribabalin.memorymcp.service;

import ru.iuribabalin.memorymcp.entity.PipelineStep;

public class PipelineRunNotAWatcherException extends RuntimeException {
    public PipelineRunNotAWatcherException(Long runId, int orderIndex, PipelineStep.ContentType actual) {
        super("Step " + orderIndex + " of run " + runId + " is type " + actual
                + ", not WATCHER - pipeline_run_step_check only applies to a watcher step");
    }
}
