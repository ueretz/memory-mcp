package ru.iuribabalin.memorymcp.dto;

public record SetupInfo(
        String mcpAddCommand,
        String mcpServerUrl,
        String skillInstallPath
) {
}
