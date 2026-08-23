package ru.iuribabalin.memorymcp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.iuribabalin.memorymcp.entity.UsageEvent;
import ru.iuribabalin.memorymcp.repository.UsageEventRepository;

import java.time.Instant;

@Service
public class UsageEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageEventRecorder.class);

    private final UsageEventRepository repository;

    public UsageEventRecorder(UsageEventRepository repository) {
        this.repository = repository;
    }

    /** Never throws - a broken stats write must never break the memory/task operation it followed. */
    public void record(UsageEvent.Action action, String entryName, String projectScope, String taskKey, String createdBy) {
        try {
            UsageEvent event = new UsageEvent();
            event.setAction(action);
            event.setEntryName(entryName);
            event.setProjectScope(projectScope);
            event.setTaskKey(taskKey);
            event.setCreatedBy(createdBy);
            event.setOccurredAt(Instant.now());
            repository.save(event);
        } catch (RuntimeException ex) {
            log.warn("Failed to record usage event {} for entry {}", action, entryName, ex);
        }
    }
}
