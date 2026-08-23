package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.iuribabalin.memorymcp.entity.MemoryNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MemoryNodeRepository extends JpaRepository<MemoryNode, Long> {

    Optional<MemoryNode> findByName(String name);

    long countByProjectScopeAndTaskIsNull(String projectScope);

    @Query(value = """
            select project_scope from memory_nodes where project_scope is not null
            union
            select project_scope from tasks
            """, nativeQuery = true)
    List<String> findAllProjectScopes();

    /**
     * taskFilterMode: "NONE" (no task_id constraint), "COMMON" (task_id IS NULL - project-level
     * entries only), or "TASK" (task_id = :taskId).
     */
    @Query("""
            select n from MemoryNode n
            where (:type is null or n.type = :type)
            and (:projectScope is null or n.projectScope = :projectScope)
            and (
                :taskFilterMode = 'NONE'
                or (:taskFilterMode = 'COMMON' and n.task is null)
                or (:taskFilterMode = 'TASK' and n.task.id = :taskId)
            )
            order by n.updatedAt desc
            """)
    List<MemoryNode> listByFilters(@Param("type") MemoryNode.Type type,
                                    @Param("projectScope") String projectScope,
                                    @Param("taskFilterMode") String taskFilterMode,
                                    @Param("taskId") Long taskId,
                                    Pageable pageable);

    @Query(value = """
            select n.* from memory_nodes n
            where n.search_vector @@ plainto_tsquery('english', :query)
            and (:type is null or n.type = :type)
            and (:projectScope is null or n.project_scope = :projectScope)
            and (
                :taskFilterMode = 'NONE'
                or (:taskFilterMode = 'COMMON' and n.task_id is null)
                or (:taskFilterMode = 'TASK' and n.task_id = :taskId)
            )
            order by ts_rank(n.search_vector, plainto_tsquery('english', :query)) desc
            """, nativeQuery = true)
    List<MemoryNode> search(@Param("query") String query,
                            @Param("type") String type,
                            @Param("projectScope") String projectScope,
                            @Param("taskFilterMode") String taskFilterMode,
                            @Param("taskId") Long taskId,
                            Pageable pageable);

    interface TypeCountRow {
        MemoryNode.Type getType();
        long getCnt();
    }

    interface TopEntryRow {
        String getName();
        MemoryNode.Type getType();
        String getDescription();
        String getProjectScope();
        String getTaskKey();
        long getCnt();
    }

    @Query("""
            select n.type as type, count(n) as cnt from MemoryNode n
            where (:projectScope is null or n.projectScope = :projectScope)
            and (:taskId is null or n.task.id = :taskId)
            group by n.type
            """)
    List<TypeCountRow> countGroupedByType(@Param("projectScope") String projectScope,
                                           @Param("taskId") Long taskId);

    @Query(value = """
            select n.name as name, n.type as type, n.description as description,
                   n.project_scope as "projectScope", t.task_key as "taskKey", count(ue.id) as cnt
            from memory_nodes n
            join usage_events ue on ue.entry_name = n.name
            left join tasks t on t.id = n.task_id
            where ue.occurred_at >= :since
            and ue.action in ('GET','RELATED')
            and (:projectScope is null or n.project_scope = :projectScope)
            and (:taskId is null or n.task_id = :taskId)
            group by n.id, n.name, n.type, n.description, n.project_scope, t.task_key
            order by cnt desc
            limit :limit
            """, nativeQuery = true)
    List<TopEntryRow> topAccessedEntries(@Param("since") Instant since,
                                          @Param("projectScope") String projectScope,
                                          @Param("taskId") Long taskId,
                                          @Param("limit") int limit);
}
