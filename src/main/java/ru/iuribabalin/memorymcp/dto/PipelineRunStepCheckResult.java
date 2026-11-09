package ru.iuribabalin.memorymcp.dto;

import java.time.Instant;

/**
 * What one check of a step the agent is working reported back.
 *
 * <p>For a WATCHER this is one probe: the server owns the deadline so the agent never has to do the
 * arithmetic - while {@code expired} is false it keeps waiting, and the moment it turns true the step
 * is closed with the {@code timeout} outcome.
 *
 * <p>For a PROMPT_LOOP this is one finished iteration: {@code iteration} of {@code maxIterations} is
 * done, and {@code done} says whether to run the step's prompt again. Keeping the count on the server
 * is what lets a run survive the session that was driving it.
 */
public record PipelineRunStepCheckResult(int checkCount, Instant deadlineAt, boolean expired,
                                          Integer iteration, Integer maxIterations, Boolean done) {

    /** Watcher shape: a probe has no iteration count. */
    public PipelineRunStepCheckResult(int checkCount, Instant deadlineAt, boolean expired) {
        this(checkCount, deadlineAt, expired, null, null, null);
    }
}
