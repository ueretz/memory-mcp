package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.FolderSummary;
import ru.iuribabalin.memorymcp.entity.Folder;
import ru.iuribabalin.memorymcp.repository.FolderRepository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class FolderService {

    private final FolderRepository folderRepository;
    private final TaskService taskService;

    public FolderService(FolderRepository folderRepository, TaskService taskService) {
        this.folderRepository = folderRepository;
        this.taskService = taskService;
    }

    @Transactional
    public FolderSummary create(String projectScope, String taskKey, String name, String description,
                                 String parentFolder, String createdBy) {
        Instant now = Instant.now();
        Folder folder = folderRepository.findByName(name).orElseGet(Folder::new);
        boolean isNew = folder.getId() == null;
        folder.setName(name);
        folder.setDescription(description);
        folder.setProjectScope(projectScope);
        folder.setTask(taskKey != null ? taskService.resolve(projectScope, taskKey) : null);
        folder.setParent(resolveParent(parentFolder, projectScope, taskKey));
        if (isNew) {
            folder.setCreatedBy(createdBy);
            folder.setCreatedAt(now);
        }
        folder.setUpdatedAt(now);
        return toSummary(folderRepository.save(folder));
    }

    @Transactional(readOnly = true)
    public List<FolderSummary> listChildren(String projectScope, String taskKey, String parentFolder) {
        Long taskId = taskKey != null ? taskService.resolve(projectScope, taskKey).getId() : null;
        return folderRepository.listChildren(parentFolder, projectScope, taskId).stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public FolderSummary get(String name) {
        return folderRepository.findByName(name).map(this::toSummary)
                .orElseThrow(() -> new FolderNotFoundException(name));
    }

    private Folder resolveParent(String parentFolder, String projectScope, String taskKey) {
        if (parentFolder == null) {
            return null;
        }
        Folder parent = folderRepository.findByName(parentFolder)
                .orElseThrow(() -> new FolderNotFoundException(parentFolder));
        String parentTaskKey = parent.getTask() != null ? parent.getTask().getTaskKey() : null;
        if (!Objects.equals(parent.getProjectScope(), projectScope) || !Objects.equals(parentTaskKey, taskKey)) {
            throw new IllegalArgumentException(
                    "Parent folder '%s' belongs to a different project/task scope".formatted(parentFolder));
        }
        return parent;
    }

    private FolderSummary toSummary(Folder folder) {
        return new FolderSummary(
                folder.getName(),
                folder.getDescription(),
                folder.getProjectScope(),
                folder.getTask() != null ? folder.getTask().getTaskKey() : null,
                folder.getParent() != null ? folder.getParent().getName() : null,
                folder.getCreatedBy(),
                folder.getUpdatedAt());
    }
}
