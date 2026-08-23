package ru.iuribabalin.memorymcp.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.entity.UsageEvent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class UsageEventRepositoryTest {

    @Autowired
    private UsageEventRepository repository;

    @Test
    void countsEventsGroupedByDayWithinScope() {
        Instant now = Instant.now();
        saveEvent(UsageEvent.Action.SAVE, "proj-a", now);
        saveEvent(UsageEvent.Action.GET, "proj-a", now);
        saveEvent(UsageEvent.Action.SAVE, "proj-b", now);
        saveEvent(UsageEvent.Action.SAVE, "proj-a", now.minus(40, ChronoUnit.DAYS));

        List<UsageEventRepository.DailyCountRow> rows =
                repository.countByDay(now.minus(7, ChronoUnit.DAYS), "proj-a", null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCnt()).isEqualTo(2);
    }

    private void saveEvent(UsageEvent.Action action, String projectScope, Instant occurredAt) {
        UsageEvent event = new UsageEvent();
        event.setAction(action);
        event.setProjectScope(projectScope);
        event.setOccurredAt(occurredAt);
        repository.saveAndFlush(event);
    }
}
