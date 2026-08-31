package ru.iuribabalin.memorymcp.service;

public class PipelineSlugTakenException extends RuntimeException {
    public PipelineSlugTakenException(String slug) {
        super("A pipeline with slug '" + slug + "' already exists - pick a different slug");
    }
}
