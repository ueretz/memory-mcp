package ru.iuribabalin.memorymcp.service;

public class PipelineAssetNotFoundException extends RuntimeException {
    public PipelineAssetNotFoundException(Long id) {
        super("No pipeline asset with id " + id);
    }
}
