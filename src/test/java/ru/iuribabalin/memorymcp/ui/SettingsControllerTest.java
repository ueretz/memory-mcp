package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.iuribabalin.memorymcp.dto.SettingSummary;
import ru.iuribabalin.memorymcp.service.SettingsService;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SettingsControllerTest {

    @Mock
    private SettingsService settingsService;

    @InjectMocks
    private SettingsController settingsController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(settingsController).build();
    }

    @Test
    void listsAllSettings() throws Exception {
        when(settingsService.listAll())
                .thenReturn(List.of(new SettingSummary("feature.pipelines.enabled", "false", Instant.now())));

        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("feature.pipelines.enabled"));
    }

    @Test
    void updatesASetting() throws Exception {
        when(settingsService.set(eq("feature.pipelines.enabled"), eq("true")))
                .thenReturn(new SettingSummary("feature.pipelines.enabled", "true", Instant.now()));

        mockMvc.perform(put("/api/settings/feature.pipelines.enabled")
                        .contentType("application/json")
                        .content("{\"value\":\"true\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("true"));
    }
}
