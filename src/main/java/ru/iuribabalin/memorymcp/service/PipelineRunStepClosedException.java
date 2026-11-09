package ru.iuribabalin.memorymcp.service;

import ru.iuribabalin.memorymcp.entity.PipelineRunStep;

/**
 * A watcher is the one step kind whose background poller can outlive the step: the agent closes the
 * step the moment it sees the event, while the last probe may still be in flight. Such a late probe
 * must not bump the step's check count or hear "keep waiting" back.
 */
public class PipelineRunStepClosedException extends RuntimeException {
    public PipelineRunStepClosedException(Long runId, int orderIndex, PipelineRunStep.Status status) {
        super("Step " + orderIndex + " of run " + runId + " is already " + status
                + " - stop probing it; its outcome has been reported");
    }
}
