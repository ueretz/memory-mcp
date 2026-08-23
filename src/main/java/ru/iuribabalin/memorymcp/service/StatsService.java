package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.StatsOverview;
import ru.iuribabalin.memorymcp.repository.MemoryNodeRepository;
import ru.iuribabalin.memorymcp.repository.UsageEventRepository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class StatsService {

    private final MemoryNodeRepository nodeRepository;
    private final UsageEventRepository eventRepository;
    private final TaskService taskService;

    public StatsService(MemoryNodeRepository nodeRepository, UsageEventRepository eventRepository, TaskService taskService) {
        this.nodeRepository = nodeRepository;
        this.eventRepository = eventRepository;
        this.taskService = taskService;
    }

    @Transactional(readOnly = true)
    public StatsOverview overview(String projectScope, String taskKey, int days) {
        int window = days > 0 ? days : 30;
        Instant since = Instant.now().minus(window, ChronoUnit.DAYS);
        // taskKey only makes sense together with a projectScope - same contract as memory_save.
        Long taskId = (projectScope != null && taskKey != null)
                ? taskService.resolve(projectScope, taskKey).getId()
                : null;
        String scopedTaskKey = projectScope != null ? taskKey : null;

        List<StatsOverview.DailyActivity> activityByDay = eventRepository
                .countByDay(since, projectScope, scopedTaskKey)
                .stream()
                .map(row -> new StatsOverview.DailyActivity(row.getDay().atZone(ZoneOffset.UTC).toLocalDate(), row.getCnt()))
                .toList();

        List<StatsOverview.TypeBreakdown> byType = nodeRepository
                .countGroupedByType(projectScope, taskId)
                .stream()
                .map(row -> new StatsOverview.TypeBreakdown(row.getType(), row.getCnt()))
                .toList();

        List<StatsOverview.TopEntry> topEntries = nodeRepository
                .topAccessedEntries(since, projectScope, taskId, 10)
                .stream()
                .map(row -> new StatsOverview.TopEntry(
                        row.getName(), row.getType(), row.getDescription(),
                        row.getProjectScope(), row.getTaskKey(), row.getCnt()))
                .toList();

        long totalEntries = byType.stream().mapToLong(StatsOverview.TypeBreakdown::count).sum();
        long totalEvents = activityByDay.stream().mapToLong(StatsOverview.DailyActivity::count).sum();

        return new StatsOverview(new StatsOverview.Totals(totalEntries, totalEvents), activityByDay, byType, topEntries);
    }
}
