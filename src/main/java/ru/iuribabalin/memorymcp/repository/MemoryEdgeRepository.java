package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.iuribabalin.memorymcp.entity.MemoryEdge;

import java.util.List;

public interface MemoryEdgeRepository extends JpaRepository<MemoryEdge, Long> {

    List<MemoryEdge> findBySourceId(Long sourceId);

    List<MemoryEdge> findByTargetId(Long targetId);

    void deleteBySourceId(Long sourceId);

    @Modifying
    @Query(value = "update memory_edges set target_id = :newTargetId where target_name = :targetName and target_id is null",
            nativeQuery = true)
    int resolveDanglingEdges(@Param("targetName") String targetName, @Param("newTargetId") Long newTargetId);
}
