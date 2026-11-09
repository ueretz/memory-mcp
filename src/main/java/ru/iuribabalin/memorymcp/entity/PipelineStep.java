package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pipeline_steps")
public class PipelineStep {

    /**
     * PROMPT / MD_FILE, PROMPT-mode CONDITION and WATCHER steps are worked on by Claude. VALUE-mode
     * CONDITION, VARIABLE, PARALLEL, JOIN and LOOP are executed by the server.
     */
    public enum ContentType { PROMPT, MD_FILE, CONDITION, VARIABLE, PARALLEL, JOIN, LOOP, WATCHER }

    /** What a WATCHER step watches. Its outcome keys follow from this (and the aspect). */
    public enum WatchSource { GITLAB_MR, COMMAND }

    /** Which side of a merge request a GITLAB_MR watcher follows. */
    public enum WatchAspect { PIPELINE, REVIEW }

    public enum ConditionMode { VALUE, PROMPT }

    public enum ConditionOperator { EQUALS, GREATER_THAN, LESS_THAN, GREATER_OR_EQUAL, LESS_OR_EQUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_id", nullable = false)
    private Long pipelineId;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    private ContentType contentType;

    @Column(name = "prompt_text", columnDefinition = "text")
    private String promptText;

    @Column(name = "asset_id")
    private Long assetId;

    @Column(name = "reference_asset_id")
    private Long referenceAssetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_operator", length = 20)
    private ConditionOperator conditionOperator;

    @Column(name = "condition_value", length = 500)
    private String conditionValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_mode", nullable = false, length = 20)
    private ConditionMode conditionMode;

    @Column(name = "max_iterations")
    private Integer maxIterations;

    @Enumerated(EnumType.STRING)
    @Column(name = "watch_source", length = 20)
    private WatchSource watchSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "watch_aspect", length = 20)
    private WatchAspect watchAspect;

    @Column(name = "check_interval_seconds")
    private Integer checkIntervalSeconds;

    @Column(name = "watch_timeout_seconds")
    private Integer watchTimeoutSeconds;

    @Column(name = "position_x", nullable = false)
    private double positionX;

    @Column(name = "position_y", nullable = false)
    private double positionY;

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public ContentType getContentType() {
        return contentType;
    }

    public void setContentType(ContentType contentType) {
        this.contentType = contentType;
    }

    public String getPromptText() {
        return promptText;
    }

    public void setPromptText(String promptText) {
        this.promptText = promptText;
    }

    public Long getAssetId() {
        return assetId;
    }

    public void setAssetId(Long assetId) {
        this.assetId = assetId;
    }

    public Long getReferenceAssetId() {
        return referenceAssetId;
    }

    public void setReferenceAssetId(Long referenceAssetId) {
        this.referenceAssetId = referenceAssetId;
    }

    public ConditionOperator getConditionOperator() {
        return conditionOperator;
    }

    public void setConditionOperator(ConditionOperator conditionOperator) {
        this.conditionOperator = conditionOperator;
    }

    public String getConditionValue() {
        return conditionValue;
    }

    public void setConditionValue(String conditionValue) {
        this.conditionValue = conditionValue;
    }

    public ConditionMode getConditionMode() {
        return conditionMode;
    }

    public void setConditionMode(ConditionMode conditionMode) {
        this.conditionMode = conditionMode;
    }

    public Integer getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(Integer maxIterations) {
        this.maxIterations = maxIterations;
    }

    public WatchSource getWatchSource() {
        return watchSource;
    }

    public void setWatchSource(WatchSource watchSource) {
        this.watchSource = watchSource;
    }

    public WatchAspect getWatchAspect() {
        return watchAspect;
    }

    public void setWatchAspect(WatchAspect watchAspect) {
        this.watchAspect = watchAspect;
    }

    public Integer getCheckIntervalSeconds() {
        return checkIntervalSeconds;
    }

    public void setCheckIntervalSeconds(Integer checkIntervalSeconds) {
        this.checkIntervalSeconds = checkIntervalSeconds;
    }

    public Integer getWatchTimeoutSeconds() {
        return watchTimeoutSeconds;
    }

    public void setWatchTimeoutSeconds(Integer watchTimeoutSeconds) {
        this.watchTimeoutSeconds = watchTimeoutSeconds;
    }

    public double getPositionX() {
        return positionX;
    }

    public void setPositionX(double positionX) {
        this.positionX = positionX;
    }

    public double getPositionY() {
        return positionY;
    }

    public void setPositionY(double positionY) {
        this.positionY = positionY;
    }
}
