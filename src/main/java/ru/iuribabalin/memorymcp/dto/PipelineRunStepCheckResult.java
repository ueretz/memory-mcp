package ru.iuribabalin.memorymcp.dto;

import java.time.Instant;

/**
 * What one watcher probe reported back. The server owns the deadline so the agent never has to do
 * the arithmetic: while {@code expired} is false it keeps waiting, and the moment it turns true the
 * step is closed with the {@code timeout} outcome.
 */
public record PipelineRunStepCheckResult(int checkCount, Instant deadlineAt, boolean expired) {
}
