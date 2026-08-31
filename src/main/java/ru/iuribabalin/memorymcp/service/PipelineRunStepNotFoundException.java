package ru.iuribabalin.memorymcp.service;

public class PipelineRunStepNotFoundException extends RuntimeException {
    public PipelineRunStepNotFoundException(Long runId, int orderIndex) {
        super("Pipeline run " + runId + " has no step at index " + orderIndex);
    }
}
