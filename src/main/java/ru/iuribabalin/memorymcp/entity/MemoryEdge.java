package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "memory_edges")
public class MemoryEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "source_id", nullable = false)
    private MemoryNode source;

    @ManyToOne
    @JoinColumn(name = "target_id")
    private MemoryNode target;

    @Column(name = "target_name", nullable = false, length = 200)
    private String targetName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public MemoryNode getSource() {
        return source;
    }

    public void setSource(MemoryNode source) {
        this.source = source;
    }

    public MemoryNode getTarget() {
        return target;
    }

    public void setTarget(MemoryNode target) {
        this.target = target;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
