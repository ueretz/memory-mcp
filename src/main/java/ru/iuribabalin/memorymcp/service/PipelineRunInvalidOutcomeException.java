package ru.iuribabalin.memorymcp.service;

import java.util.List;

public class PipelineRunInvalidOutcomeException extends RuntimeException {
    public PipelineRunInvalidOutcomeException(String outcome, List<String> validOutcomes) {
        super("Outcome '" + outcome + "' is not valid for this step — expected one of: " + String.join(", ", validOutcomes));
    }
}
