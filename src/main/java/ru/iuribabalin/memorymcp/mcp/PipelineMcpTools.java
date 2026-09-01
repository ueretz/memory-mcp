package ru.iuribabalin.memorymcp.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.iuribabalin.memorymcp.dto.PipelineExecutionDetail;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.dto.PipelineSummary;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.UsageEvent;
import ru.iuribabalin.memorymcp.service.PipelineFeatureDisabledException;
import ru.iuribabalin.memorymcp.service.PipelineRunService;
import ru.iuribabalin.memorymcp.service.PipelineService;
import ru.iuribabalin.memorymcp.service.SettingsService;
import ru.iuribabalin.memorymcp.service.UsageEventRecorder;

import java.util.List;

@Component
public class PipelineMcpTools {

    private final PipelineService pipelineService;
    private final PipelineRunService pipelineRunService;
    private final SettingsService settingsService;
    private final UsageEventRecorder usageEventRecorder;

    public PipelineMcpTools(PipelineService pipelineService, PipelineRunService pipelineRunService,
                             SettingsService settingsService, UsageEventRecorder usageEventRecorder) {
        this.pipelineService = pipelineService;
        this.pipelineRunService = pipelineRunService;
        this.settingsService = settingsService;
        this.usageEventRecorder = usageEventRecorder;
    }

    @McpTool(name = "pipeline_list",
            description = "List hand-authored pipelines available for a project - each is a named, linear sequence " +
                    "of steps built in the memory-mcp dashboard. Call this to resolve a pipeline name the user " +
                    "mentioned into its exact slug before pipeline_get/pipeline_run_start. Disabled unless the " +
                    "'Pipelines' experimental feature is turned on in dashboard Settings.")
    public List<PipelineSummary> pipelineList(
            @McpToolParam(description = "Project identifier, auto-derived from the git repo name", required = true) String projectScope) {
        requireEnabled();
        return pipelineService.list(projectScope);
    }

    @McpTool(name = "pipeline_get",
            description = "Fetch a pipeline's full definition - its ordered steps and parameters. Uploaded .md step " +
                    "content and any optional reference attachment are inlined as plain text, no separate download " +
                    "needed. Call before pipeline_run_start so you know what parameters to ask the user for.")
    public PipelineExecutionDetail pipelineGet(
            @McpToolParam(description = "The pipeline's slug, from pipeline_list", required = true) String slug) {
        requireEnabled();
        return pipelineService.getForExecution(slug);
    }

    @McpTool(name = "pipeline_run_start",
            description = "Start a run of a pipeline: validates required parameters are present, snapshots its " +
                    "current steps, and returns a runId plus the ordered step list to work through. Call " +
                    "pipeline_run_step_update after finishing each step, in order, and pipeline_run_complete once " +
                    "every step is done or the run is being abandoned.")
    public PipelineRunDetail pipelineRunStart(
            @McpToolParam(description = "The pipeline's slug", required = true) String slug,
            @McpToolParam(description = "JSON object of parameter values keyed by parameter name, e.g. {\"folder\": \"src/config\"} - include every required parameter", required = false) String parametersJson) {
        requireEnabled();
        pipelineService.validateParameters(slug, parametersJson);
        PipelineRunDetail run = pipelineRunService.start(slug, parametersJson, null);
        usageEventRecorder.record(UsageEvent.Action.PIPELINE_RUN_START, slug, null, null, null);
        return run;
    }

    @McpTool(name = "pipeline_run_step_update",
            description = "Report the outcome of one pipeline run step after doing its work: RUNNING when you start " +
                    "it, then DONE or FAILED when you finish, or SKIPPED if the user told you to skip it. Include a " +
                    "short note describing what you did or why it failed. On FAILED, stop and ask the user how to " +
                    "proceed before calling this again - do not silently continue to the next step.")
    public PipelineRunDetail pipelineRunStepUpdate(
            @McpToolParam(description = "The run id, from pipeline_run_start", required = true) Long runId,
            @McpToolParam(description = "0-based index of the step in the run's step list", required = true) Integer orderIndex,
            @McpToolParam(description = "New status: RUNNING, DONE, FAILED, or SKIPPED", required = true) PipelineRunStep.Status status,
            @McpToolParam(description = "Short summary of what happened for this step", required = false) String note) {
        requireEnabled();
        PipelineRunDetail run = pipelineRunService.updateStep(runId, orderIndex, status, note, null);
        usageEventRecorder.record(UsageEvent.Action.PIPELINE_RUN_STEP_UPDATE, String.valueOf(runId), null, null, null);
        return run;
    }

    @McpTool(name = "pipeline_run_complete",
            description = "Finish a pipeline run once every step is done, or to mark it FAILED/ABORTED if it's being " +
                    "given up on partway through.")
    public PipelineRunDetail pipelineRunComplete(
            @McpToolParam(description = "The run id", required = true) Long runId,
            @McpToolParam(description = "Final status: DONE, FAILED, or ABORTED", required = true) PipelineRun.Status status) {
        requireEnabled();
        PipelineRunDetail run = pipelineRunService.complete(runId, status);
        usageEventRecorder.record(UsageEvent.Action.PIPELINE_RUN_COMPLETE, String.valueOf(runId), null, null, null);
        return run;
    }

    @McpTool(name = "pipeline_run_get",
            description = "Fetch a pipeline run's current state - use this to resume a run a previous session left " +
                    "mid-way, to see which steps are already done.")
    public PipelineRunDetail pipelineRunGet(
            @McpToolParam(description = "The run id", required = true) Long runId) {
        requireEnabled();
        return pipelineRunService.get(runId);
    }

    private void requireEnabled() {
        if (!settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)) {
            throw new PipelineFeatureDisabledException();
        }
    }
}
