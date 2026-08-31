package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.SettingSummary;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SettingsServiceTest {

    @Autowired
    private SettingsService settingsService;

    @Test
    void pipelinesFlagStartsDisabled() {
        assertThat(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).isFalse();
    }

    @Test
    void settingCanBeEnabledAndReadBack() {
        settingsService.set(SettingsService.PIPELINES_ENABLED, "true");

        assertThat(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).isTrue();
    }

    @Test
    void unknownKeyIsTreatedAsDisabled() {
        assertThat(settingsService.isEnabled("feature.does-not-exist")).isFalse();
    }

    @Test
    void listAllIncludesTheSeedRow() {
        assertThat(settingsService.listAll())
                .extracting(SettingSummary::key)
                .contains(SettingsService.PIPELINES_ENABLED);
    }
}
