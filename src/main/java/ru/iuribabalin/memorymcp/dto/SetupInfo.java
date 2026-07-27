package ru.iuribabalin.memorymcp.dto;

public record SetupInfo(
        String mcpAddCommand,
        String jarPath,
        String javaExecutable,
        String skillInstallPath
) {
}
