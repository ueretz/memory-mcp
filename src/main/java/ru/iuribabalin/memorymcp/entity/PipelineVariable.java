package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A named constant on the pipeline's input plate, next to the run parameters. The difference from a
 * parameter is only where the value comes from: a parameter is typed in when a run starts, a
 * variable is written once by the pipeline's author.
 *
 * <p>A variable is deliberately NOT a step. It performs no work, so it never belonged in the
 * transition graph - standing there only forced authors to chain variables in a line to get
 * several of them onto a board. Its value is resolved once at the start of a run (see
 * {@code PipelineRunService}) and wired wherever it is needed through a
 * {@link PipelineDataLink}, which can also carry one variable's value into another's
 * {@code {{data:token}}}.
 */
@Entity
@Table(name = "pipeline_variables")
public class PipelineVariable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_id", nullable = false)
    private Long pipelineId;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String value;

    public Long getId() {
        return id;
    }

    public Long getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(Long pipelineId) {
        this.pipelineId = pipelineId;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
