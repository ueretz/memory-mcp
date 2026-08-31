package ru.iuribabalin.memorymcp.service;

public class PipelineNotFoundException extends RuntimeException {
    public PipelineNotFoundException(String slug) {
        super("No pipeline with slug '" + slug + "' - call pipeline_list to see what's available");
    }
}
