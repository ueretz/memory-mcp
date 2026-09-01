package ru.iuribabalin.memorymcp.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.iuribabalin.memorymcp.dto.PipelineExecutionDetail;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.service.PipelineFeatureDisabledException;
import ru.iuribabalin.memorymcp.service.PipelineRunService;
import ru.iuribabalin.memorymcp.service.PipelineService;
import ru.iuribabalin.memorymcp.service.SettingsService;
import ru.iuribabalin.memorymcp.service.UsageEventRecorder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineMcpToolsTest {

    @Mock
    private PipelineService pipelineService;
    @Mock
    private PipelineRunService pipelineRunService;
    @Mock
    private SettingsService settingsService;
    @Mock
    private UsageEventRecorder usageEventRecorder;

    @InjectMocks
    private PipelineMcpTools pipelineMcpTools;

    @BeforeEach
    void setUp() {
    }

    @Test
    void throwsWhenTheFeatureFlagIsDisabled() {
        when(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).thenReturn(false);

        assertThatThrownBy(() -> pipelineMcpTools.pipelineList("memory-mcp"))
                .isInstanceOf(PipelineFeatureDisabledException.class);
    }

    @Test
    void pipelineGetDelegatesWhenEnabled() {
        when(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).thenReturn(true);
        PipelineExecutionDetail detail = new PipelineExecutionDetail("config-diff", "Config diff", "desc", List.of(), List.of());
        when(pipelineService.getForExecution("config-diff")).thenReturn(detail);

        PipelineExecutionDetail result = pipelineMcpTools.pipelineGet("config-diff");

        assertThat(result).isEqualTo(detail);
    }

    @Test
    void runStartValidatesParametersAndRecordsUsage() {
        when(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).thenReturn(true);
        PipelineRunDetail runDetail = new PipelineRunDetail(1L, 1L, "config-diff", PipelineRun.Status.RUNNING, "{}", Instant.now(), null, null, 0, List.of());
        when(pipelineRunService.start("config-diff", "{}", null)).thenReturn(runDetail);

        PipelineRunDetail result = pipelineMcpTools.pipelineRunStart("config-diff", "{}");

        assertThat(result).isEqualTo(runDetail);
        verify(pipelineService).validateParameters("config-diff", "{}");
        verify(usageEventRecorder).record(ru.iuribabalin.memorymcp.entity.UsageEvent.Action.PIPELINE_RUN_START, "config-diff", null, null, null);
    }

    @Test
    void runStepUpdateDelegatesAndRecordsUsage() {
        when(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).thenReturn(true);
        PipelineRunDetail runDetail = new PipelineRunDetail(1L, 1L, "config-diff", PipelineRun.Status.RUNNING, "{}", Instant.now(), null, null, 0, List.of());
        when(pipelineRunService.updateStep(1L, 0, PipelineRunStep.Status.DONE, "ok", null)).thenReturn(runDetail);

        PipelineRunDetail result = pipelineMcpTools.pipelineRunStepUpdate(1L, 0, PipelineRunStep.Status.DONE, "ok");

        assertThat(result).isEqualTo(runDetail);
        verify(usageEventRecorder).record(ru.iuribabalin.memorymcp.entity.UsageEvent.Action.PIPELINE_RUN_STEP_UPDATE, "1", null, null, null);
    }
}
