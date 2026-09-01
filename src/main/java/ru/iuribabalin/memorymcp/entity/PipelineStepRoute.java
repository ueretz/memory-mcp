package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pipeline_step_routes")
public class PipelineStepRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Column(name = "outcome_key", length = 100)
    private String outcomeKey;

    @Column(name = "target_step_id")
    private Long targetStepId;

    public Long getId() {
        return id;
    }

    public Long getStepId() {
        return stepId;
    }

    public void setStepId(Long stepId) {
        this.stepId = stepId;
    }

    public String getOutcomeKey() {
        return outcomeKey;
    }

    public void setOutcomeKey(String outcomeKey) {
        this.outcomeKey = outcomeKey;
    }

    public Long getTargetStepId() {
        return targetStepId;
    }

    public void setTargetStepId(Long targetStepId) {
        this.targetStepId = targetStepId;
    }
}
