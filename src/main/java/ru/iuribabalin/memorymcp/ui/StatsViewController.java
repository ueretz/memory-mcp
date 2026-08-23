package ru.iuribabalin.memorymcp.ui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.StatsOverview;
import ru.iuribabalin.memorymcp.service.StatsService;

@RestController
public class StatsViewController {

    private final StatsService statsService;

    public StatsViewController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/api/stats/overview")
    public StatsOverview overview(
            @RequestParam(required = false) String projectScope,
            @RequestParam(required = false) String taskKey,
            @RequestParam(required = false, defaultValue = "30") int days) {
        return statsService.overview(projectScope, taskKey, days);
    }
}
