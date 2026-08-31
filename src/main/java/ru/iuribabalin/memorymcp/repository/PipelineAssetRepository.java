package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineAsset;

public interface PipelineAssetRepository extends JpaRepository<PipelineAsset, Long> {
}
