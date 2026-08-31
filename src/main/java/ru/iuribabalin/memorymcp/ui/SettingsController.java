package ru.iuribabalin.memorymcp.ui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.SettingSummary;
import ru.iuribabalin.memorymcp.service.SettingsService;

import java.util.List;
import java.util.Map;

@RestController
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/api/settings")
    public List<SettingSummary> list() {
        return settingsService.listAll();
    }

    @PutMapping("/api/settings/{key}")
    public SettingSummary set(@PathVariable String key, @RequestBody Map<String, String> body) {
        return settingsService.set(key, body.get("value"));
    }
}
