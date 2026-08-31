package ru.iuribabalin.memorymcp.service;

public class PipelineRunNotFoundException extends RuntimeException {
    public PipelineRunNotFoundException(Long runId) {
        super("No pipeline run with id " + runId);
    }
}
