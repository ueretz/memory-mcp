package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.Pipeline;

import java.util.List;
import java.util.Optional;

public interface PipelineRepository extends JpaRepository<Pipeline, Long> {
    Optional<Pipeline> findBySlug(String slug);
    List<Pipeline> findByProjectScopeOrderByUpdatedAtDesc(String projectScope);
}
