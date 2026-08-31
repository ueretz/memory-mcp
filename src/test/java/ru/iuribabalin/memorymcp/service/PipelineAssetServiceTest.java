package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineAssetSummary;
import ru.iuribabalin.memorymcp.entity.PipelineAsset;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PipelineAssetServiceTest {

    @Autowired
    private PipelineAssetService pipelineAssetService;

    @Test
    void uploadsAndReadsBackAsText() {
        byte[] data = "# Report template".getBytes(StandardCharsets.UTF_8);

        PipelineAssetSummary summary = pipelineAssetService.upload("template.md", "text/markdown", data, "Tester");

        assertThat(summary.filename()).isEqualTo("template.md");
        assertThat(summary.sizeBytes()).isEqualTo(data.length);
        assertThat(pipelineAssetService.readAsText(summary.id())).isEqualTo("# Report template");
    }

    @Test
    void getReturnsTheStoredEntity() {
        PipelineAssetSummary summary = pipelineAssetService.upload("notes.md", "text/markdown", "hi".getBytes(StandardCharsets.UTF_8), null);

        PipelineAsset asset = pipelineAssetService.get(summary.id());

        assertThat(asset.getFilename()).isEqualTo("notes.md");
    }

    @Test
    void throwsForAnUnknownId() {
        assertThatThrownBy(() -> pipelineAssetService.get(-1L))
                .isInstanceOf(PipelineAssetNotFoundException.class);
    }
}
