package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "usage_events")
public class UsageEvent {

    public enum Action {
        SAVE, GET, LIST, SEARCH, GRAPH, RELATED, DELETE, TASK_START, TASK_CLOSE, FOLDER_CREATE,
        AGENT_TASK_CREATE, AGENT_TASK_UPDATE, AGENT_TASK_DELETE, AGENT_TASK_CLAIM
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    @Column(name = "entry_name", length = 500)
    private String entryName;

    @Column(name = "project_scope", length = 200)
    private String projectScope;

    @Column(name = "task_key", length = 100)
    private String taskKey;

    @Column(name = "created_by", length = 300)
    private String createdBy;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public Long getId() {
        return id;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public String getEntryName() {
        return entryName;
    }

    public void setEntryName(String entryName) {
        this.entryName = entryName;
    }

    public String getProjectScope() {
        return projectScope;
    }

    public void setProjectScope(String projectScope) {
        this.projectScope = projectScope;
    }

    public String getTaskKey() {
        return taskKey;
    }

    public void setTaskKey(String taskKey) {
        this.taskKey = taskKey;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
