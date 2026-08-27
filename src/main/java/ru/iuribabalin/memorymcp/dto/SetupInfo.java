package ru.iuribabalin.memorymcp.dto;

import java.util.List;

public record SetupInfo(
        String mcpAddCommand,
        String mcpServerUrl,
        List<SkillInfo> skills
) {
}
