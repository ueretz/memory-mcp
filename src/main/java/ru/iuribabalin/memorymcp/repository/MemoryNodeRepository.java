package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.iuribabalin.memorymcp.entity.MemoryNode;

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
}
