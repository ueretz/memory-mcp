package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.SettingSummary;
import ru.iuribabalin.memorymcp.entity.Setting;
import ru.iuribabalin.memorymcp.repository.SettingRepository;

import java.time.Instant;
import java.util.List;

@Service
public class SettingsService {

    public static final String PIPELINES_ENABLED = "feature.pipelines.enabled";

    private final SettingRepository settingRepository;

    public SettingsService(SettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    @Transactional(readOnly = true)
    public List<SettingSummary> listAll() {
        return settingRepository.findAll().stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(String key) {
        return settingRepository.findById(key)
                .map(setting -> Boolean.parseBoolean(setting.getValue()))
                .orElse(false);
    }

    @Transactional
    public SettingSummary set(String key, String value) {
        Setting setting = settingRepository.findById(key).orElseGet(() -> {
            Setting created = new Setting();
            created.setKey(key);
            return created;
        });
        setting.setValue(value);
        setting.setUpdatedAt(Instant.now());
        return toSummary(settingRepository.save(setting));
    }

    private SettingSummary toSummary(Setting setting) {
        return new SettingSummary(setting.getKey(), setting.getValue(), setting.getUpdatedAt());
    }
}
