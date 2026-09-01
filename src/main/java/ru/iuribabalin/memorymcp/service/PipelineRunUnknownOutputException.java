package ru.iuribabalin.memorymcp.service;

import java.util.List;

public class PipelineRunUnknownOutputException extends RuntimeException {
    public PipelineRunUnknownOutputException(String outputName, List<String> validNames) {
        super("Output '" + outputName + "' is not declared for this step — expected one of: " + String.join(", ", validNames));
    }
}
