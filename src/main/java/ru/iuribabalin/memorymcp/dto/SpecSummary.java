package ru.iuribabalin.memorymcp.dto;

import java.time.Instant;

public record SpecSummary(String name, String description, Instant updatedAt) {
}
