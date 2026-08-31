package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineAssetSummary;
import ru.iuribabalin.memorymcp.entity.PipelineAsset;
import ru.iuribabalin.memorymcp.repository.PipelineAssetRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Service
public class PipelineAssetService {

    private final PipelineAssetRepository pipelineAssetRepository;

    public PipelineAssetService(PipelineAssetRepository pipelineAssetRepository) {
        this.pipelineAssetRepository = pipelineAssetRepository;
    }

    @Transactional
    public PipelineAssetSummary upload(String filename, String contentType, byte[] data, String createdBy) {
        PipelineAsset asset = new PipelineAsset();
        asset.setFilename(filename);
        asset.setContentType(contentType != null ? contentType : "text/plain");
        asset.setSizeBytes(data.length);
        asset.setData(data);
        asset.setCreatedAt(Instant.now());
        asset.setCreatedBy(createdBy);
        return toSummary(pipelineAssetRepository.save(asset));
    }

    @Transactional(readOnly = true)
    public PipelineAsset get(Long id) {
        return pipelineAssetRepository.findById(id)
                .orElseThrow(() -> new PipelineAssetNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public String readAsText(Long id) {
        return new String(get(id).getData(), StandardCharsets.UTF_8);
    }

    private PipelineAssetSummary toSummary(PipelineAsset asset) {
        return new PipelineAssetSummary(asset.getId(), asset.getFilename(), asset.getContentType(), asset.getSizeBytes(), asset.getCreatedAt());
    }
}
