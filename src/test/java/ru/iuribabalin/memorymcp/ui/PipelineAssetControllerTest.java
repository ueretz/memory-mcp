package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.iuribabalin.memorymcp.dto.PipelineAssetSummary;
import ru.iuribabalin.memorymcp.entity.PipelineAsset;
import ru.iuribabalin.memorymcp.service.PipelineAssetNotFoundException;
import ru.iuribabalin.memorymcp.service.PipelineAssetService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PipelineAssetControllerTest {

    @Mock
    private PipelineAssetService pipelineAssetService;

    @InjectMocks
    private PipelineAssetController pipelineAssetController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(pipelineAssetController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void uploadsAFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "template.md", "text/markdown", "# hi".getBytes(StandardCharsets.UTF_8));
        when(pipelineAssetService.upload(eq("template.md"), eq("text/markdown"), any(), eq(null)))
                .thenReturn(new PipelineAssetSummary(1L, "template.md", "text/markdown", 4L, Instant.now()));

        mockMvc.perform(multipart("/api/pipeline-assets").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("template.md"));
    }

    @Test
    void downloadReturns404ForUnknownAsset() throws Exception {
        when(pipelineAssetService.get(99L)).thenThrow(new PipelineAssetNotFoundException(99L));

        mockMvc.perform(get("/api/pipeline-assets/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadReturnsTheFileBytes() throws Exception {
        PipelineAsset asset = new PipelineAsset();
        asset.setFilename("template.md");
        asset.setContentType("text/markdown");
        asset.setData("# hi".getBytes(StandardCharsets.UTF_8));
        when(pipelineAssetService.get(1L)).thenReturn(asset);

        mockMvc.perform(get("/api/pipeline-assets/1"))
                .andExpect(status().isOk());
    }
}
