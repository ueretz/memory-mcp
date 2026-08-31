package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineParameter;

import java.util.List;

public record PipelineExecutionDetail(
        String slug, String name, String description,
        List<ParameterView> parameters, List<StepView> steps) {

    public record ParameterView(String name, String label, PipelineParameter.Type type, boolean required, String defaultValue) {
    }

    public record StepView(int orderIndex, String title, String instructionText, String referenceText) {
    }
}
