package ru.iuribabalin.memorymcp.dto;

import java.time.Instant;

public record SettingSummary(String key, String value, Instant updatedAt) {
}
