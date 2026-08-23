package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.SaveMemoryRequest;
import ru.iuribabalin.memorymcp.dto.StatsOverview;
import ru.iuribabalin.memorymcp.entity.MemoryNode;
import ru.iuribabalin.memorymcp.entity.UsageEvent;
import ru.iuribabalin.memorymcp.repository.UsageEventRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class StatsServiceTest {

    @Autowired
    private StatsService statsService;
    @Autowired
    private MemoryService memoryService;
    @Autowired
    private UsageEventRepository usageEventRepository;

    @Test
    void overviewAggregatesEntriesAndEventsForAProject() {
        memoryService.save(new SaveMemoryRequest(
                "stats-test-entry", MemoryNode.Type.PROJECT, "desc", "content",
                "stats-test-project", null, null, "Tester <t@example.com>"));

        UsageEvent get = new UsageEvent();
        get.setAction(UsageEvent.Action.GET);
        get.setEntryName("stats-test-entry");
        get.setProjectScope("stats-test-project");
        get.setOccurredAt(Instant.now());
        usageEventRepository.saveAndFlush(get);

        StatsOverview overview = statsService.overview("stats-test-project", null, 30);

        assertThat(overview.totals().totalEntries()).isEqualTo(1);
        assertThat(overview.byType()).hasSize(1);
        assertThat(overview.byType().get(0).type()).isEqualTo(MemoryNode.Type.PROJECT);
        assertThat(overview.topEntries()).hasSize(1);
        assertThat(overview.topEntries().get(0).name()).isEqualTo("stats-test-entry");
        assertThat(overview.topEntries().get(0).accessCount()).isEqualTo(1);
    }
}
