package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.iuribabalin.memorymcp.entity.Folder;

import java.util.List;
import java.util.Optional;

public interface FolderRepository extends JpaRepository<Folder, Long> {

    Optional<Folder> findByName(String name);

    long deleteByProjectScopeAndTaskIsNull(String projectScope);

    @Query(value = """
            select f.* from folders f
            where f.project_scope = :projectScope
            and ((:taskId is null and f.task_id is null) or f.task_id = :taskId)
            and ((:parentName is null and f.parent_id is null) or f.parent_id = (select id from folders where name = :parentName))
            order by f.name
            """, nativeQuery = true)
    List<Folder> listChildren(@Param("parentName") String parentName,
                               @Param("projectScope") String projectScope,
                               @Param("taskId") Long taskId);
}
