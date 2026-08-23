package ru.iuribabalin.memorymcp.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.GraphResponse;
import ru.iuribabalin.memorymcp.dto.MemoryEntryDetail;
import ru.iuribabalin.memorymcp.dto.MemoryEntrySummary;
import ru.iuribabalin.memorymcp.dto.SaveMemoryRequest;
import ru.iuribabalin.memorymcp.entity.Folder;
import ru.iuribabalin.memorymcp.entity.MemoryEdge;
import ru.iuribabalin.memorymcp.entity.MemoryNode;
import ru.iuribabalin.memorymcp.entity.Task;
import ru.iuribabalin.memorymcp.repository.FolderRepository;
import ru.iuribabalin.memorymcp.repository.MemoryEdgeRepository;
import ru.iuribabalin.memorymcp.repository.MemoryNodeRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class MemoryService {

    private final MemoryNodeRepository nodeRepository;
    private final MemoryEdgeRepository edgeRepository;
    private final LinkParser linkParser;
    private final TaskService taskService;
    private final FolderRepository folderRepository;

    public MemoryService(MemoryNodeRepository nodeRepository, MemoryEdgeRepository edgeRepository,
                          LinkParser linkParser, TaskService taskService, FolderRepository folderRepository) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.linkParser = linkParser;
        this.taskService = taskService;
        this.folderRepository = folderRepository;
    }

    @Transactional
    public MemoryEntryDetail save(SaveMemoryRequest request) {
        Instant now = Instant.now();
        MemoryNode node = nodeRepository.findByName(request.name()).orElseGet(MemoryNode::new);
        boolean isNew = node.getId() == null;
        node.setName(request.name());
        node.setType(request.type());
        node.setDescription(request.description());
        node.setContent(request.content());
        node.setProjectScope(request.projectScope());
        node.setFilePath(request.filePath());
        node.setFolder(resolveFolder(request.projectScope(), request.taskKey(), request.folder()));
        if (request.taskKey() != null) {
            if (request.projectScope() == null) {
                throw new IllegalArgumentException("projectScope is required when taskKey is set");
            }
            node.setTask(taskService.resolve(request.projectScope(), request.taskKey()));
        } else {
            node.setTask(null);
        }
        if (isNew) {
            node.setCreatedAt(now);
        }
        node.setUpdatedAt(now);
        node = nodeRepository.save(node);

        edgeRepository.deleteBySourceId(node.getId());
        Set<String> linkedNames = linkParser.extractLinkedNames(request.content());
        for (String linkedName : linkedNames) {
            if (linkedName.equals(node.getName())) {
                continue;
            }
            MemoryEdge edge = new MemoryEdge();
            edge.setSource(node);
            edge.setTargetName(linkedName);
            edge.setCreatedAt(now);
            nodeRepository.findByName(linkedName).ifPresent(edge::setTarget);
            edgeRepository.save(edge);
        }

        edgeRepository.resolveDanglingEdges(node.getName(), node.getId());

        return toDetail(node);
    }

    @Transactional(readOnly = true)
    public MemoryEntryDetail get(String name) {
        MemoryNode node = nodeRepository.findByName(name).orElseThrow(() -> new MemoryNotFoundException(name));
        return toDetail(node);
    }

    @Transactional(readOnly = true)
    public List<MemoryEntrySummary> list(MemoryNode.Type type, String projectScope, String taskKey, String folderName, int limit, int offset) {
        int pageSize = limit > 0 ? limit : 50;
        int page = pageSize > 0 ? offset / pageSize : 0;
        Pageable pageable = PageRequest.of(page, pageSize);
        TaskFilter taskFilter = resolveTaskFilter(projectScope, taskKey);
        FolderFilter folderFilter = resolveFolderFilter(folderName);
        return nodeRepository.listByFilters(type, projectScope, taskFilter.mode(), taskFilter.taskId(),
                        folderFilter.mode(), folderFilter.name(), pageable).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MemoryEntrySummary> search(String query, MemoryNode.Type type, String projectScope, String taskKey, String folderName, int limit) {
        int pageSize = limit > 0 ? limit : 20;
        String typeName = type != null ? type.name() : null;
        TaskFilter taskFilter = resolveTaskFilter(projectScope, taskKey);
        FolderFilter folderFilter = resolveSearchFolderFilter(folderName);
        return nodeRepository.search(query, typeName, projectScope, taskFilter.mode(), taskFilter.taskId(),
                        folderFilter.mode(), folderFilter.name(), PageRequest.of(0, pageSize)).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public GraphResponse graph(MemoryNode.Type type, String projectScope, String taskKey) {
        TaskFilter taskFilter = resolveTaskFilter(projectScope, taskKey);
        List<MemoryNode> nodes = nodeRepository.listByFilters(type, projectScope, taskFilter.mode(), taskFilter.taskId(), "NONE", null, Pageable.unpaged());
        Set<Long> nodeIds = nodes.stream().map(MemoryNode::getId).collect(java.util.stream.Collectors.toSet());

        List<GraphResponse.GraphNode> graphNodes = nodes.stream()
                .map(n -> new GraphResponse.GraphNode(n.getName(), n.getType()))
                .toList();

        List<GraphResponse.GraphEdge> graphEdges = new ArrayList<>();
        for (MemoryNode node : nodes) {
            for (MemoryEdge edge : edgeRepository.findBySourceId(node.getId())) {
                MemoryNode target = edge.getTarget();
                if (target != null && nodeIds.contains(target.getId())) {
                    graphEdges.add(new GraphResponse.GraphEdge(node.getName(), target.getName()));
                }
            }
        }
        return new GraphResponse(graphNodes, graphEdges);
    }

    @Transactional(readOnly = true)
    public List<MemoryEntrySummary> related(String name, int depth) {
        MemoryNode node = nodeRepository.findByName(name).orElseThrow(() -> new MemoryNotFoundException(name));
        Map<String, MemoryEntrySummary> related = new LinkedHashMap<>();

        for (MemoryEdge edge : edgeRepository.findBySourceId(node.getId())) {
            if (edge.getTarget() != null) {
                related.put(edge.getTarget().getName(), toSummary(edge.getTarget()));
            }
        }
        for (MemoryEdge edge : edgeRepository.findByTargetId(node.getId())) {
            related.put(edge.getSource().getName(), toSummary(edge.getSource()));
        }
        return new ArrayList<>(related.values());
    }

    @Transactional
    public boolean delete(String name) {
        return nodeRepository.findByName(name)
                .map(node -> {
                    nodeRepository.delete(node);
                    return true;
                })
                .orElse(false);
    }

    /**
     * No projectScope -> no task-scope filtering at all ("NONE"). ProjectScope but no taskKey
     * -> project-level "common" entries only ("COMMON", task_id IS NULL). Both -> a specific
     * task's entries ("TASK", task_id = resolved id).
     */
    private TaskFilter resolveTaskFilter(String projectScope, String taskKey) {
        if (projectScope == null) {
            return new TaskFilter("NONE", null);
        }
        if (taskKey == null) {
            return new TaskFilter("COMMON", null);
        }
        Task task = taskService.resolve(projectScope, taskKey);
        return new TaskFilter("TASK", task.getId());
    }

    private record TaskFilter(String mode, Long taskId) {
    }

    private Folder resolveFolder(String projectScope, String taskKey, String folderName) {
        if (folderName == null) {
            return null;
        }
        Folder folder = folderRepository.findByName(folderName)
                .orElseThrow(() -> new FolderNotFoundException(folderName));
        String folderTaskKey = folder.getTask() != null ? folder.getTask().getTaskKey() : null;
        if (!Objects.equals(folder.getProjectScope(), projectScope) || !Objects.equals(folderTaskKey, taskKey)) {
            throw new IllegalArgumentException(
                    "Folder '%s' belongs to a different project/task scope".formatted(folderName));
        }
        return folder;
    }

    /** No folder given -> browsing the root (folder IS NULL), matching a file-explorer model. */
    private FolderFilter resolveFolderFilter(String folderName) {
        return folderName != null ? new FolderFilter("IN", folderName) : new FolderFilter("ROOT", null);
    }

    /** Search defaults to everywhere in scope, not root-only - unlike browsing, folders shouldn't make entries unfindable. */
    private FolderFilter resolveSearchFolderFilter(String folderName) {
        return folderName != null ? new FolderFilter("IN", folderName) : new FolderFilter("NONE", null);
    }

    private record FolderFilter(String mode, String name) {
    }

    private MemoryEntryDetail toDetail(MemoryNode node) {
        List<MemoryEntrySummary> linkedTo = edgeRepository.findBySourceId(node.getId()).stream()
                .map(MemoryEdge::getTarget)
                .filter(java.util.Objects::nonNull)
                .map(this::toSummary)
                .toList();
        List<MemoryEntrySummary> linkedFrom = edgeRepository.findByTargetId(node.getId()).stream()
                .map(edge -> toSummary(edge.getSource()))
                .toList();
        return new MemoryEntryDetail(
                node.getName(),
                node.getType(),
                node.getDescription(),
                node.getContent(),
                node.getProjectScope(),
                node.getTask() != null ? node.getTask().getTaskKey() : null,
                node.getFolder() != null ? node.getFolder().getName() : null,
                node.getFilePath(),
                node.getCreatedAt(),
                node.getUpdatedAt(),
                linkedTo,
                linkedFrom,
                scopeWarnings(node, linkedTo)
        );
    }

    /**
     * Catches the case that silently orphaned 18 GCBE-10157 entries: an unscoped memory_save
     * links into an entry that already belongs to a task, so the new entry almost certainly
     * should have carried the same projectScope/taskKey.
     */
    private List<String> scopeWarnings(MemoryNode node, List<MemoryEntrySummary> linkedTo) {
        if (node.getTask() != null) {
            return List.of();
        }
        return linkedTo.stream()
                .filter(linked -> linked.taskKey() != null)
                .map(linked -> "'%s' has no task scope but links to '%s', which belongs to task %s (project %s). "
                        .formatted(node.getName(), linked.name(), linked.taskKey(), linked.projectScope())
                        + "Pass projectScope/taskKey on memory_save if this entry belongs to that task.")
                .distinct()
                .toList();
    }

    private MemoryEntrySummary toSummary(MemoryNode node) {
        return new MemoryEntrySummary(
                node.getName(),
                node.getType(),
                node.getDescription(),
                node.getProjectScope(),
                node.getTask() != null ? node.getTask().getTaskKey() : null,
                node.getFolder() != null ? node.getFolder().getName() : null,
                node.getFilePath(),
                node.getUpdatedAt()
        );
    }
}
