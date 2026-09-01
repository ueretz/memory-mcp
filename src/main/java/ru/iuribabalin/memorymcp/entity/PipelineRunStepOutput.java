package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pipeline_run_step_outputs")
public class PipelineRunStepOutput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_step_id", nullable = false)
    private Long runStepId;

    @Column(name = "output_id", nullable = false)
    private Long outputId;

    @Column(nullable = false, columnDefinition = "text")
    private String value;

    public Long getId() {
        return id;
    }

    public Long getRunStepId() {
        return runStepId;
    }

    public void setRunStepId(Long runStepId) {
        this.runStepId = runStepId;
    }

    public Long getOutputId() {
        return outputId;
    }

    public void setOutputId(Long outputId) {
        this.outputId = outputId;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
