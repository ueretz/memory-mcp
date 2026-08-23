package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.iuribabalin.memorymcp.dto.StatsOverview;
import ru.iuribabalin.memorymcp.service.StatsService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class StatsViewControllerTest {

    @Mock
    private StatsService statsService;

    @InjectMocks
    private StatsViewController statsViewController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(statsViewController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsOverviewForAScopedProject() throws Exception {
        StatsOverview overview = new StatsOverview(
                new StatsOverview.Totals(3, 5),
                List.of(),
                List.of(),
                List.of());
        when(statsService.overview(eq("memory-mcp"), any(), anyInt())).thenReturn(overview);

        mockMvc.perform(get("/api/stats/overview").param("projectScope", "memory-mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.totalEntries").value(3))
                .andExpect(jsonPath("$.totals.totalEvents").value(5));
    }
}
