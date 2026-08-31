package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.iuribabalin.memorymcp.service.MemoryService;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MemoryViewControllerTest {

    @Mock
    private MemoryService memoryService;

    @InjectMocks
    private MemoryViewController memoryViewController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(memoryViewController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void deletesAnExistingEntry() throws Exception {
        when(memoryService.delete("some-entry")).thenReturn(true);

        mockMvc.perform(delete("/api/memory/some-entry"))
                .andExpect(status().isNoContent());
    }

    @Test
    void returns404WhenEntryDoesNotExist() throws Exception {
        when(memoryService.delete("missing-entry")).thenReturn(false);

        mockMvc.perform(delete("/api/memory/missing-entry"))
                .andExpect(status().isNotFound());
    }
}
