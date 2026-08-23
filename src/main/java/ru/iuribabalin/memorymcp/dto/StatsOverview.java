package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.MemoryNode;

import java.time.LocalDate;
import java.util.List;

public record StatsOverview(
        Totals totals,
        List<DailyActivity> activityByDay,
        List<TypeBreakdown> byType,
        List<TopEntry> topEntries
) {
    public record Totals(long totalEntries, long totalEvents) {
    }

    public record DailyActivity(LocalDate day, long count) {
    }

    public record TypeBreakdown(MemoryNode.Type type, long count) {
    }

    public record TopEntry(String name, MemoryNode.Type type, String description,
                            String projectScope, String taskKey, long accessCount) {
    }
}
