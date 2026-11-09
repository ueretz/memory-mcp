package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A wire carrying a value into a {@code {{data:token}}} placeholder.
 *
 * <p>The source is a step output ({@code sourceStepId} + {@code sourceOutputId}), a pipeline
 * parameter ({@code sourceParameterId}) or a pipeline variable ({@code sourceVariableId}) - exactly
 * one of the three (enforced by a CHECK constraint, V17 and V24).
 *
 * <p>The target is a step ({@code targetStepId}) or another variable ({@code targetVariableId}) -
 * again exactly one, so a variable's value can be assembled out of a parameter or of other
 * variables without any of them becoming a step (V24).
 */
@Entity
@Table(name = "pipeline_data_links")
public class PipelineDataLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String token;

    @Column(name = "source_step_id")
    private Long sourceStepId;

    @Column(name = "source_output_id")
    private Long sourceOutputId;

    @Column(name = "source_parameter_id")
    private Long sourceParameterId;

    @Column(name = "source_variable_id")
    private Long sourceVariableId;

    @Column(name = "target_step_id")
    private Long targetStepId;

    @Column(name = "target_variable_id")
    private Long targetVariableId;

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Long getSourceStepId() {
        return sourceStepId;
    }

    public void setSourceStepId(Long sourceStepId) {
        this.sourceStepId = sourceStepId;
    }

    public Long getSourceOutputId() {
        return sourceOutputId;
    }

    public void setSourceOutputId(Long sourceOutputId) {
        this.sourceOutputId = sourceOutputId;
    }

    public Long getSourceParameterId() {
        return sourceParameterId;
    }

    public void setSourceParameterId(Long sourceParameterId) {
        this.sourceParameterId = sourceParameterId;
    }

    public Long getSourceVariableId() {
        return sourceVariableId;
    }

    public void setSourceVariableId(Long sourceVariableId) {
        this.sourceVariableId = sourceVariableId;
    }

    public boolean isParameterSourced() {
        return sourceParameterId != null;
    }

    public boolean isVariableSourced() {
        return sourceVariableId != null;
    }

    public Long getTargetStepId() {
        return targetStepId;
    }

    public void setTargetStepId(Long targetStepId) {
        this.targetStepId = targetStepId;
    }

    public Long getTargetVariableId() {
        return targetVariableId;
    }

    public void setTargetVariableId(Long targetVariableId) {
        this.targetVariableId = targetVariableId;
    }
}
