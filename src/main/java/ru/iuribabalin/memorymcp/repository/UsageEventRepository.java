package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.iuribabalin.memorymcp.entity.UsageEvent;

import java.time.Instant;
import java.util.List;

public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {

    interface DailyCountRow {
        Instant getDay();
        long getCnt();
    }

    /**
     * projectScope/taskKey null means "no filter on that dimension" - a project with no
     * taskKey returns activity for the whole project (common entries and every task), not
     * just its task-less "common" slice, unlike MemoryService's stricter COMMON/TASK modes.
     */
    @Query(value = """
            select date_trunc('day', occurred_at) as day, count(*) as cnt
            from usage_events
            where occurred_at >= :since
            and (:projectScope is null or project_scope = :projectScope)
            and (:taskKey is null or task_key = :taskKey)
            group by day
            order by day
            """, nativeQuery = true)
    List<DailyCountRow> countByDay(@Param("since") Instant since,
                                    @Param("projectScope") String projectScope,
                                    @Param("taskKey") String taskKey);

    long deleteByProjectScope(String projectScope);
}
