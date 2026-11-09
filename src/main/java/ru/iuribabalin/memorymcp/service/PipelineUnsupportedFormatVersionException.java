package ru.iuribabalin.memorymcp.service;

public class PipelineUnsupportedFormatVersionException extends RuntimeException {
    public PipelineUnsupportedFormatVersionException(int formatVersion) {
        super("Unsupported pipeline export formatVersion " + formatVersion + "; supported version is 1");
    }
}
