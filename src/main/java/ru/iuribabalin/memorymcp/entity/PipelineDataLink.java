package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A wire carrying a value into a step's {@code {{data:token}}} placeholder. The source is either a
 * step output ({@code sourceStepId} + {@code sourceOutputId}) or a pipeline parameter
 * ({@code sourceParameterId}) - exactly one of the two (enforced by a CHECK constraint, V17).
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

    @Column(name = "target_step_id", nullable = false)
    private Long targetStepId;

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

    public boolean isParameterSourced() {
        return sourceParameterId != null;
    }

    public Long getTargetStepId() {
        return targetStepId;
    }

    public void setTargetStepId(Long targetStepId) {
        this.targetStepId = targetStepId;
    }
}
