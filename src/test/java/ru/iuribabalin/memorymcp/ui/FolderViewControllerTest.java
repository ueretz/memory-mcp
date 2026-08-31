package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.iuribabalin.memorymcp.dto.FolderSummary;
import ru.iuribabalin.memorymcp.service.FolderService;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FolderViewControllerTest {

    @Mock
    private FolderService folderService;

    @InjectMocks
    private FolderViewController folderViewController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(folderViewController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsTopLevelFoldersForAProject() throws Exception {
        FolderSummary folder = new FolderSummary("docs", "desc", "memory-mcp", null, null, "Tester", Instant.now());
        when(folderService.listChildren("memory-mcp", null, null)).thenReturn(List.of(folder));

        mockMvc.perform(get("/api/folders").param("projectScope", "memory-mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("docs"));
    }

    @Test
    void getsAFolderByName() throws Exception {
        FolderSummary folder = new FolderSummary("docs", "desc", "memory-mcp", null, null, "Tester", Instant.now());
        when(folderService.get("docs")).thenReturn(folder);

        mockMvc.perform(get("/api/folders/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("desc"));
    }

    @Test
    void deletesAnExistingFolder() throws Exception {
        when(folderService.delete("docs")).thenReturn(true);

        mockMvc.perform(delete("/api/folders/docs"))
                .andExpect(status().isNoContent());
    }

    @Test
    void returns404WhenDeletingAMissingFolder() throws Exception {
        when(folderService.delete("missing")).thenReturn(false);

        mockMvc.perform(delete("/api/folders/missing"))
                .andExpect(status().isNotFound());
    }
}
