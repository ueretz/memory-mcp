# Experimental Pipelines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user hand-build named, linear "pipelines" of steps in the memory-mcp dashboard (behind an experimental-features flag), then trigger a whole pipeline from Claude Code chat and watch it execute step-by-step with tracked, resumable, browsable history.

**Architecture:** memory-mcp stores pipeline definitions/run state only; Claude Code (via a new skill) is the execution engine, using its normal tools and reporting progress back through new read/execute-only MCP tools. Dashboard REST endpoints (`ui/` package) are the only way to author pipelines. A generic `settings` key-value table gates both the UI nav and the MCP tools.

**Tech Stack:** Spring Boot 4.1 (Java 25), Spring Data JPA/Hibernate, PostgreSQL 17 + Flyway, Spring AI MCP Server annotations (`@McpTool`), Vue 3 (`<script setup>` + TS) + Vue Router 5 + Tailwind 4, plain `fetch` (no axios).

**Spec:** `docs/superpowers/specs/2026-08-31-experimental-pipelines-design.md`

## Global Constraints

- No Lombok anywhere — plain JPA entities with explicit getters/setters (id has no setter), matching every existing entity.
- FK relations to another aggregate root are stored as raw `Long xId` columns (the `AgentTask` convention), not `@ManyToOne` object references, for every new entity here — matches how `pipeline_run_steps`/`pipeline_steps`/etc. need to be queried and kept independent of JPA cascade surprises.
- Timestamps are `java.time.Instant`, set manually in the service via `Instant.now()` — never `@CreationTimestamp`/`@UpdateTimestamp`.
- Enums are Postgres `VARCHAR + CHECK IN (...)`, mapped with `@Enumerated(EnumType.STRING)` — never native Postgres enum types.
- Flyway migrations: `BIGSERIAL PRIMARY KEY`, snake_case columns, explicit `VARCHAR(n)` lengths, named indexes `idx_<table>_<column>`, named unique constraints `ux_<table>_<column>`, `TIMESTAMPTZ NOT NULL DEFAULT now()` for every timestamp column. `ddl-auto: validate` is on — migrations must match entity mappings exactly or the app fails to start.
- Domain exceptions are minimal message-only `RuntimeException` subclasses; every new one gets a matching `@ExceptionHandler` added to `ui/ApiExceptionHandler.java`.
- MCP tool classes: `@Component`, constructor injection, one `@McpTool`-annotated method per tool, `@McpToolParam` on every parameter, mutating tools record a `UsageEvent` via `UsageEventRecorder` after success.
- REST controllers in `ui/` package have no class-level `@RequestMapping` — full paths on each method.
- Frontend: no new dependency (no axios, no DnD library, no test framework — the project has none; verification is `npm run type-check`). New TS types go in `ui/src/api/types.ts` with the file's existing "mirrors the backend DTOs" comment convention; new API functions go in `ui/src/api/client.ts`.
- Backend tests run against the real local Postgres (`docker compose up -d`, port 5433) via `@SpringBootTest @Transactional` (service layer) or Mockito `standaloneSetup` MockMvc slice tests (controller layer) — no Testcontainers, no mocks at the service-test layer.

---

## Task 1: Settings (generic feature-flag mechanism)

**Files:**
- Create: `src/main/resources/db/migration/V9__add_settings.sql`
- Create: `src/main/java/ru/iuribabalin/memorymcp/entity/Setting.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/repository/SettingRepository.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/dto/SettingSummary.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/SettingsService.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/ui/SettingsController.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/SettingsServiceTest.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/ui/SettingsControllerTest.java`

**Interfaces:**
- Produces: `SettingsService.PIPELINES_ENABLED` (`String` constant = `"feature.pipelines.enabled"`), `SettingsService.isEnabled(String key): boolean`, `SettingsService.listAll(): List<SettingSummary>`, `SettingsService.set(String key, String value): SettingSummary`. `SettingSummary(String key, String value, Instant updatedAt)`. Task 5 (`PipelineMcpTools`) consumes `isEnabled`/`PIPELINES_ENABLED` directly.

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE settings (
    key         VARCHAR(200) PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO settings (key, value, updated_at) VALUES ('feature.pipelines.enabled', 'false', now());
```

- [ ] **Step 2: Write the entity**

```java
package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "settings")
public class Setting {

    @Id
    @Column(length = 200)
    private String key;

    @Column(nullable = false, columnDefinition = "text")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
```

- [ ] **Step 3: Write the repository**

```java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.Setting;

public interface SettingRepository extends JpaRepository<Setting, String> {
}
```

- [ ] **Step 4: Write the DTO**

```java
package ru.iuribabalin.memorymcp.dto;

import java.time.Instant;

public record SettingSummary(String key, String value, Instant updatedAt) {
}
```

- [ ] **Step 5: Write the failing service test**

```java
package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SettingsServiceTest {

    @Autowired
    private SettingsService settingsService;

    @Test
    void pipelinesFlagStartsDisabled() {
        assertThat(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).isFalse();
    }

    @Test
    void settingCanBeEnabledAndReadBack() {
        settingsService.set(SettingsService.PIPELINES_ENABLED, "true");

        assertThat(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).isTrue();
    }

    @Test
    void unknownKeyIsTreatedAsDisabled() {
        assertThat(settingsService.isEnabled("feature.does-not-exist")).isFalse();
    }

    @Test
    void listAllIncludesTheSeedRow() {
        assertThat(settingsService.listAll())
                .extracting(SettingSummary::key)
                .contains(SettingsService.PIPELINES_ENABLED);
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.SettingsServiceTest"`
Expected: FAIL to compile — `SettingsService` doesn't exist yet.

- [ ] **Step 7: Write the service**

```java
package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.SettingSummary;
import ru.iuribabalin.memorymcp.entity.Setting;
import ru.iuribabalin.memorymcp.repository.SettingRepository;

import java.time.Instant;
import java.util.List;

@Service
public class SettingsService {

    public static final String PIPELINES_ENABLED = "feature.pipelines.enabled";

    private final SettingRepository settingRepository;

    public SettingsService(SettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    @Transactional(readOnly = true)
    public List<SettingSummary> listAll() {
        return settingRepository.findAll().stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(String key) {
        return settingRepository.findById(key)
                .map(setting -> Boolean.parseBoolean(setting.getValue()))
                .orElse(false);
    }

    @Transactional
    public SettingSummary set(String key, String value) {
        Setting setting = settingRepository.findById(key).orElseGet(() -> {
            Setting created = new Setting();
            created.setKey(key);
            return created;
        });
        setting.setValue(value);
        setting.setUpdatedAt(Instant.now());
        return toSummary(settingRepository.save(setting));
    }

    private SettingSummary toSummary(Setting setting) {
        return new SettingSummary(setting.getKey(), setting.getValue(), setting.getUpdatedAt());
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `docker compose up -d && ./gradlew test --tests "ru.iuribabalin.memorymcp.service.SettingsServiceTest"`
Expected: PASS (4 tests)

- [ ] **Step 9: Write the controller**

```java
package ru.iuribabalin.memorymcp.ui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.SettingSummary;
import ru.iuribabalin.memorymcp.service.SettingsService;

import java.util.List;
import java.util.Map;

@RestController
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/api/settings")
    public List<SettingSummary> list() {
        return settingsService.listAll();
    }

    @PutMapping("/api/settings/{key}")
    public SettingSummary set(@PathVariable String key, @RequestBody Map<String, String> body) {
        return settingsService.set(key, body.get("value"));
    }
}
```

- [ ] **Step 10: Write the controller test**

```java
package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.iuribabalin.memorymcp.dto.SettingSummary;
import ru.iuribabalin.memorymcp.service.SettingsService;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SettingsControllerTest {

    @Mock
    private SettingsService settingsService;

    @InjectMocks
    private SettingsController settingsController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(settingsController).build();
    }

    @Test
    void listsAllSettings() throws Exception {
        when(settingsService.listAll())
                .thenReturn(List.of(new SettingSummary("feature.pipelines.enabled", "false", Instant.now())));

        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("feature.pipelines.enabled"));
    }

    @Test
    void updatesASetting() throws Exception {
        when(settingsService.set(eq("feature.pipelines.enabled"), eq("true")))
                .thenReturn(new SettingSummary("feature.pipelines.enabled", "true", Instant.now()));

        mockMvc.perform(put("/api/settings/feature.pipelines.enabled")
                        .contentType("application/json")
                        .content("{\"value\":\"true\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("true"));
    }
}
```

- [ ] **Step 11: Run all tests to verify they pass**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.SettingsServiceTest" --tests "ru.iuribabalin.memorymcp.ui.SettingsControllerTest"`
Expected: PASS (6 tests total)

- [ ] **Step 12: Commit**

```bash
git add src/main/resources/db/migration/V9__add_settings.sql src/main/java/ru/iuribabalin/memorymcp/entity/Setting.java src/main/java/ru/iuribabalin/memorymcp/repository/SettingRepository.java src/main/java/ru/iuribabalin/memorymcp/dto/SettingSummary.java src/main/java/ru/iuribabalin/memorymcp/service/SettingsService.java src/main/java/ru/iuribabalin/memorymcp/ui/SettingsController.java src/test/java/ru/iuribabalin/memorymcp/service/SettingsServiceTest.java src/test/java/ru/iuribabalin/memorymcp/ui/SettingsControllerTest.java
git commit -m "feat: add generic settings key-value store for feature flags"
```

---

## Task 2: Pipeline assets (real file upload/download)

**Files:**
- Create: `src/main/resources/db/migration/V10__add_pipeline_assets.sql`
- Create: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineAsset.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/repository/PipelineAssetRepository.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineAssetSummary.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineAssetNotFoundException.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineAssetService.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/ui/PipelineAssetController.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineAssetServiceTest.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/ui/PipelineAssetControllerTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `PipelineAssetService.upload(String filename, String contentType, byte[] data, String createdBy): PipelineAssetSummary`, `PipelineAssetService.get(Long id): PipelineAsset` (throws `PipelineAssetNotFoundException`), `PipelineAssetService.readAsText(Long id): String`. `PipelineAssetSummary(Long id, String filename, String contentType, long sizeBytes, Instant createdAt)`. Task 3's `pipeline_steps.asset_id`/`reference_asset_id` reference `pipeline_assets.id`. Task 5 (`PipelineMcpTools`/`PipelineService.getForExecution`) consumes `readAsText`.

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE pipeline_assets (
    id            BIGSERIAL PRIMARY KEY,
    filename      VARCHAR(255) NOT NULL,
    content_type  VARCHAR(120) NOT NULL,
    size_bytes    BIGINT NOT NULL,
    data          BYTEA NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(300)
);
```

- [ ] **Step 2: Write the entity**

```java
package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pipeline_assets")
public class PipelineAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Lob
    @Column(nullable = false)
    private byte[] data;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 300)
    private String createdBy;

    public Long getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
```

- [ ] **Step 3: Write the repository**

```java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineAsset;

public interface PipelineAssetRepository extends JpaRepository<PipelineAsset, Long> {
}
```

- [ ] **Step 4: Write the DTO and exception**

```java
package ru.iuribabalin.memorymcp.dto;

import java.time.Instant;

public record PipelineAssetSummary(Long id, String filename, String contentType, long sizeBytes, Instant createdAt) {
}
```

```java
package ru.iuribabalin.memorymcp.service;

public class PipelineAssetNotFoundException extends RuntimeException {
    public PipelineAssetNotFoundException(Long id) {
        super("No pipeline asset with id " + id);
    }
}
```

- [ ] **Step 5: Write the failing service test**

```java
package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineAssetSummary;
import ru.iuribabalin.memorymcp.entity.PipelineAsset;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PipelineAssetServiceTest {

    @Autowired
    private PipelineAssetService pipelineAssetService;

    @Test
    void uploadsAndReadsBackAsText() {
        byte[] data = "# Report template".getBytes(StandardCharsets.UTF_8);

        PipelineAssetSummary summary = pipelineAssetService.upload("template.md", "text/markdown", data, "Tester");

        assertThat(summary.filename()).isEqualTo("template.md");
        assertThat(summary.sizeBytes()).isEqualTo(data.length);
        assertThat(pipelineAssetService.readAsText(summary.id())).isEqualTo("# Report template");
    }

    @Test
    void getReturnsTheStoredEntity() {
        PipelineAssetSummary summary = pipelineAssetService.upload("notes.md", "text/markdown", "hi".getBytes(StandardCharsets.UTF_8), null);

        PipelineAsset asset = pipelineAssetService.get(summary.id());

        assertThat(asset.getFilename()).isEqualTo("notes.md");
    }

    @Test
    void throwsForAnUnknownId() {
        assertThatThrownBy(() -> pipelineAssetService.get(-1L))
                .isInstanceOf(PipelineAssetNotFoundException.class);
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineAssetServiceTest"`
Expected: FAIL to compile — `PipelineAssetService` doesn't exist yet.

- [ ] **Step 7: Write the service**

```java
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
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineAssetServiceTest"`
Expected: PASS (3 tests)

- [ ] **Step 9: Write the controller**

```java
package ru.iuribabalin.memorymcp.ui;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.iuribabalin.memorymcp.dto.PipelineAssetSummary;
import ru.iuribabalin.memorymcp.entity.PipelineAsset;
import ru.iuribabalin.memorymcp.service.PipelineAssetService;

import java.io.IOException;

@RestController
public class PipelineAssetController {

    private final PipelineAssetService pipelineAssetService;

    public PipelineAssetController(PipelineAssetService pipelineAssetService) {
        this.pipelineAssetService = pipelineAssetService;
    }

    @PostMapping("/api/pipeline-assets")
    public PipelineAssetSummary upload(@RequestParam("file") MultipartFile file) throws IOException {
        return pipelineAssetService.upload(file.getOriginalFilename(), file.getContentType(), file.getBytes(), null);
    }

    @GetMapping("/api/pipeline-assets/{id}")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        PipelineAsset asset = pipelineAssetService.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + asset.getFilename() + "\"")
                .body(asset.getData());
    }
}
```

- [ ] **Step 10: Add the exception handler entry**

In `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`, add the import and a new handler method, following the existing `FolderNotFoundException` entry:

```java
import ru.iuribabalin.memorymcp.service.PipelineAssetNotFoundException;
```

```java
    @ExceptionHandler(PipelineAssetNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePipelineAssetNotFound(PipelineAssetNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }
```

- [ ] **Step 11: Write the controller test**

```java
package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.iuribabalin.memorymcp.dto.PipelineAssetSummary;
import ru.iuribabalin.memorymcp.entity.PipelineAsset;
import ru.iuribabalin.memorymcp.service.PipelineAssetNotFoundException;
import ru.iuribabalin.memorymcp.service.PipelineAssetService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PipelineAssetControllerTest {

    @Mock
    private PipelineAssetService pipelineAssetService;

    @InjectMocks
    private PipelineAssetController pipelineAssetController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(pipelineAssetController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void uploadsAFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "template.md", "text/markdown", "# hi".getBytes(StandardCharsets.UTF_8));
        when(pipelineAssetService.upload(eq("template.md"), eq("text/markdown"), any(), eq(null)))
                .thenReturn(new PipelineAssetSummary(1L, "template.md", "text/markdown", 4L, Instant.now()));

        mockMvc.perform(multipart("/api/pipeline-assets").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("template.md"));
    }

    @Test
    void downloadReturns404ForUnknownAsset() throws Exception {
        when(pipelineAssetService.get(99L)).thenThrow(new PipelineAssetNotFoundException(99L));

        mockMvc.perform(get("/api/pipeline-assets/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadReturnsTheFileBytes() throws Exception {
        PipelineAsset asset = new PipelineAsset();
        asset.setFilename("template.md");
        asset.setContentType("text/markdown");
        asset.setData("# hi".getBytes(StandardCharsets.UTF_8));
        when(pipelineAssetService.get(1L)).thenReturn(asset);

        mockMvc.perform(get("/api/pipeline-assets/1"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 12: Run all tests to verify they pass**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineAssetServiceTest" --tests "ru.iuribabalin.memorymcp.ui.PipelineAssetControllerTest"`
Expected: PASS (6 tests total)

- [ ] **Step 13: Commit**

```bash
git add src/main/resources/db/migration/V10__add_pipeline_assets.sql src/main/java/ru/iuribabalin/memorymcp/entity/PipelineAsset.java src/main/java/ru/iuribabalin/memorymcp/repository/PipelineAssetRepository.java src/main/java/ru/iuribabalin/memorymcp/dto/PipelineAssetSummary.java src/main/java/ru/iuribabalin/memorymcp/service/PipelineAssetNotFoundException.java src/main/java/ru/iuribabalin/memorymcp/service/PipelineAssetService.java src/main/java/ru/iuribabalin/memorymcp/ui/PipelineAssetController.java src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java src/test/java/ru/iuribabalin/memorymcp/service/PipelineAssetServiceTest.java src/test/java/ru/iuribabalin/memorymcp/ui/PipelineAssetControllerTest.java
git commit -m "feat: add pipeline asset upload/download for md/html attachments"
```

---

## Task 3: Pipeline CRUD (definitions, parameters, steps)

**Files:**
- Create: `src/main/resources/db/migration/V11__add_pipelines.sql`
- Create: `src/main/java/ru/iuribabalin/memorymcp/entity/Pipeline.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineParameter.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStep.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/repository/PipelineRepository.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/repository/PipelineParameterRepository.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/repository/PipelineStepRepository.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineSummary.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineDetail.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineUpsertRequest.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineNotFoundException.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineSlugTakenException.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/ui/PipelineController.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/ui/PipelineControllerTest.java`

**Interfaces:**
- Consumes: nothing directly (asset ids from Task 2 are opaque `Long`s passed through).
- Produces: `PipelineService.resolve(String slug): Pipeline` (package-visible, throws `PipelineNotFoundException`), `PipelineService.list(String projectScope): List<PipelineSummary>`, `PipelineService.get(String slug): PipelineDetail`, `PipelineService.create(PipelineUpsertRequest, String createdBy): PipelineDetail`, `PipelineService.update(String slug, PipelineUpsertRequest): PipelineDetail`, `PipelineService.delete(String slug): boolean`. `Pipeline.ContentType`-adjacent enums: `PipelineParameter.Type { STRING, NUMBER, BOOLEAN }`, `PipelineStep.ContentType { PROMPT, MD_FILE }`. Task 4 consumes `PipelineRepository`, `PipelineParameterRepository`, `PipelineStepRepository`, and extends `PipelineService` with `getForExecution`/`validateParameters`. Task 5 consumes `PipelineService.list`/`getForExecution`/`validateParameters`.

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE pipelines (
    id             BIGSERIAL PRIMARY KEY,
    slug           VARCHAR(120) NOT NULL,
    name           VARCHAR(255) NOT NULL,
    description    TEXT,
    project_scope  VARCHAR(200),
    created_by     VARCHAR(300),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_pipelines_slug UNIQUE (slug)
);

CREATE INDEX idx_pipelines_project_scope ON pipelines (project_scope);

CREATE TABLE pipeline_parameters (
    id             BIGSERIAL PRIMARY KEY,
    pipeline_id    BIGINT NOT NULL REFERENCES pipelines (id) ON DELETE CASCADE,
    name           VARCHAR(100) NOT NULL,
    label          VARCHAR(255) NOT NULL,
    type           VARCHAR(20) NOT NULL CHECK (type IN ('STRING','NUMBER','BOOLEAN')),
    required       BOOLEAN NOT NULL DEFAULT false,
    default_value  TEXT,
    order_index    INTEGER NOT NULL
);

CREATE INDEX idx_pipeline_parameters_pipeline_id ON pipeline_parameters (pipeline_id);

CREATE TABLE pipeline_steps (
    id                   BIGSERIAL PRIMARY KEY,
    pipeline_id          BIGINT NOT NULL REFERENCES pipelines (id) ON DELETE CASCADE,
    order_index          INTEGER NOT NULL,
    title                VARCHAR(255) NOT NULL,
    content_type         VARCHAR(20) NOT NULL CHECK (content_type IN ('PROMPT','MD_FILE')),
    prompt_text          TEXT,
    asset_id             BIGINT REFERENCES pipeline_assets (id) ON DELETE RESTRICT,
    reference_asset_id   BIGINT REFERENCES pipeline_assets (id) ON DELETE RESTRICT
);

CREATE INDEX idx_pipeline_steps_pipeline_id ON pipeline_steps (pipeline_id);
```

- [ ] **Step 2: Write the entities**

```java
package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pipelines")
public class Pipeline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "project_scope", length = 200)
    private String projectScope;

    @Column(name = "created_by", length = 300)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getProjectScope() {
        return projectScope;
    }

    public void setProjectScope(String projectScope) {
        this.projectScope = projectScope;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
```

```java
package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pipeline_parameters")
public class PipelineParameter {

    public enum Type { STRING, NUMBER, BOOLEAN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_id", nullable = false)
    private Long pipelineId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 255)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(nullable = false)
    private boolean required;

    @Column(name = "default_value", columnDefinition = "text")
    private String defaultValue;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    public Long getId() {
        return id;
    }

    public Long getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(Long pipelineId) {
        this.pipelineId = pipelineId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }
}
```

```java
package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pipeline_steps")
public class PipelineStep {

    public enum ContentType { PROMPT, MD_FILE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_id", nullable = false)
    private Long pipelineId;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    private ContentType contentType;

    @Column(name = "prompt_text", columnDefinition = "text")
    private String promptText;

    @Column(name = "asset_id")
    private Long assetId;

    @Column(name = "reference_asset_id")
    private Long referenceAssetId;

    public Long getId() {
        return id;
    }

    public Long getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(Long pipelineId) {
        this.pipelineId = pipelineId;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public ContentType getContentType() {
        return contentType;
    }

    public void setContentType(ContentType contentType) {
        this.contentType = contentType;
    }

    public String getPromptText() {
        return promptText;
    }

    public void setPromptText(String promptText) {
        this.promptText = promptText;
    }

    public Long getAssetId() {
        return assetId;
    }

    public void setAssetId(Long assetId) {
        this.assetId = assetId;
    }

    public Long getReferenceAssetId() {
        return referenceAssetId;
    }

    public void setReferenceAssetId(Long referenceAssetId) {
        this.referenceAssetId = referenceAssetId;
    }
}
```

- [ ] **Step 3: Write the repositories**

```java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.Pipeline;

import java.util.List;
import java.util.Optional;

public interface PipelineRepository extends JpaRepository<Pipeline, Long> {
    Optional<Pipeline> findBySlug(String slug);
    List<Pipeline> findByProjectScopeOrderByUpdatedAtDesc(String projectScope);
}
```

```java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineParameter;

import java.util.List;

public interface PipelineParameterRepository extends JpaRepository<PipelineParameter, Long> {
    List<PipelineParameter> findByPipelineIdOrderByOrderIndexAsc(Long pipelineId);
    void deleteByPipelineId(Long pipelineId);
}
```

```java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.util.List;

public interface PipelineStepRepository extends JpaRepository<PipelineStep, Long> {
    List<PipelineStep> findByPipelineIdOrderByOrderIndexAsc(Long pipelineId);
    void deleteByPipelineId(Long pipelineId);
}
```

- [ ] **Step 4: Write the DTOs and exceptions**

```java
package ru.iuribabalin.memorymcp.dto;

import java.time.Instant;

public record PipelineSummary(
        Long id,
        String slug,
        String name,
        String description,
        String projectScope,
        int parameterCount,
        int stepCount,
        String createdBy,
        Instant updatedAt
) {
}
```

```java
package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.time.Instant;
import java.util.List;

public record PipelineDetail(
        Long id,
        String slug,
        String name,
        String description,
        String projectScope,
        List<PipelineParameterView> parameters,
        List<PipelineStepView> steps,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public record PipelineParameterView(
            Long id, String name, String label, PipelineParameter.Type type,
            boolean required, String defaultValue, int orderIndex) {
    }

    public record PipelineStepView(
            Long id, int orderIndex, String title, PipelineStep.ContentType contentType,
            String promptText, Long assetId, Long referenceAssetId) {
    }
}
```

```java
package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.util.List;

public record PipelineUpsertRequest(
        String slug,
        String name,
        String description,
        String projectScope,
        List<ParameterRequest> parameters,
        List<StepRequest> steps
) {
    public record ParameterRequest(String name, String label, PipelineParameter.Type type, boolean required, String defaultValue) {
    }

    public record StepRequest(String title, PipelineStep.ContentType contentType, String promptText, Long assetId, Long referenceAssetId) {
    }
}
```

```java
package ru.iuribabalin.memorymcp.service;

public class PipelineNotFoundException extends RuntimeException {
    public PipelineNotFoundException(String slug) {
        super("No pipeline with slug '" + slug + "' - call pipeline_list to see what's available");
    }
}
```

```java
package ru.iuribabalin.memorymcp.service;

public class PipelineSlugTakenException extends RuntimeException {
    public PipelineSlugTakenException(String slug) {
        super("A pipeline with slug '" + slug + "' already exists - pick a different slug");
    }
}
```

- [ ] **Step 5: Write the failing service test**

```java
package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineDetail;
import ru.iuribabalin.memorymcp.dto.PipelineSummary;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PipelineServiceTest {

    @Autowired
    private PipelineService pipelineService;

    private PipelineUpsertRequest sampleRequest(String slug) {
        return new PipelineUpsertRequest(
                slug, "Config diff", "Diffs configs against prod", "pipeline-svc-test-project",
                List.of(new PipelineUpsertRequest.ParameterRequest("folder", "Folder to check", PipelineParameter.Type.STRING, true, null)),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Check history", PipelineStep.ContentType.PROMPT, "Diff {{folder}} against prod", null, null),
                        new PipelineUpsertRequest.StepRequest("Save report", PipelineStep.ContentType.PROMPT, "Save the report to memory", null, null)));
    }

    @Test
    void createsAndFetchesAPipelineWithItsStepsAndParameters() {
        pipelineService.create(sampleRequest("config-diff-1"), "Tester");

        PipelineDetail detail = pipelineService.get("config-diff-1");

        assertThat(detail.name()).isEqualTo("Config diff");
        assertThat(detail.parameters()).extracting(PipelineDetail.PipelineParameterView::name).containsExactly("folder");
        assertThat(detail.steps()).extracting(PipelineDetail.PipelineStepView::title)
                .containsExactly("Check history", "Save report");
    }

    @Test
    void rejectsADuplicateSlug() {
        pipelineService.create(sampleRequest("config-diff-2"), "Tester");

        assertThatThrownBy(() -> pipelineService.create(sampleRequest("config-diff-2"), "Tester"))
                .isInstanceOf(PipelineSlugTakenException.class);
    }

    @Test
    void updateReplacesStepsAndParameters() {
        pipelineService.create(sampleRequest("config-diff-3"), "Tester");
        PipelineUpsertRequest updated = new PipelineUpsertRequest(
                "config-diff-3", "Config diff v2", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("Only step", PipelineStep.ContentType.PROMPT, "do it", null, null)));

        PipelineDetail detail = pipelineService.update("config-diff-3", updated);

        assertThat(detail.name()).isEqualTo("Config diff v2");
        assertThat(detail.parameters()).isEmpty();
        assertThat(detail.steps()).extracting(PipelineDetail.PipelineStepView::title).containsExactly("Only step");
    }

    @Test
    void listReturnsPipelinesForAProject() {
        pipelineService.create(sampleRequest("config-diff-4"), "Tester");

        List<PipelineSummary> pipelines = pipelineService.list("pipeline-svc-test-project");

        assertThat(pipelines).extracting(PipelineSummary::slug).contains("config-diff-4");
    }

    @Test
    void deleteRemovesThePipeline() {
        pipelineService.create(sampleRequest("config-diff-5"), "Tester");

        boolean deleted = pipelineService.delete("config-diff-5");

        assertThat(deleted).isTrue();
        assertThatThrownBy(() -> pipelineService.get("config-diff-5")).isInstanceOf(PipelineNotFoundException.class);
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineServiceTest"`
Expected: FAIL to compile — `PipelineService` doesn't exist yet.

- [ ] **Step 7: Write the service**

```java
package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineDetail;
import ru.iuribabalin.memorymcp.dto.PipelineSummary;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.entity.Pipeline;
import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import ru.iuribabalin.memorymcp.repository.PipelineParameterRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRepository;

import java.time.Instant;
import java.util.List;

@Service
public class PipelineService {

    private final PipelineRepository pipelineRepository;
    private final PipelineParameterRepository pipelineParameterRepository;
    private final PipelineStepRepository pipelineStepRepository;

    public PipelineService(PipelineRepository pipelineRepository,
                            PipelineParameterRepository pipelineParameterRepository,
                            PipelineStepRepository pipelineStepRepository) {
        this.pipelineRepository = pipelineRepository;
        this.pipelineParameterRepository = pipelineParameterRepository;
        this.pipelineStepRepository = pipelineStepRepository;
    }

    @Transactional(readOnly = true)
    public List<PipelineSummary> list(String projectScope) {
        return pipelineRepository.findByProjectScopeOrderByUpdatedAtDesc(projectScope).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public PipelineDetail get(String slug) {
        return toDetail(resolve(slug));
    }

    @Transactional
    public PipelineDetail create(PipelineUpsertRequest request, String createdBy) {
        if (pipelineRepository.findBySlug(request.slug()).isPresent()) {
            throw new PipelineSlugTakenException(request.slug());
        }
        Instant now = Instant.now();
        Pipeline pipeline = new Pipeline();
        pipeline.setSlug(request.slug());
        pipeline.setCreatedBy(createdBy);
        pipeline.setCreatedAt(now);
        applyFields(pipeline, request, now);
        pipeline = pipelineRepository.save(pipeline);
        replaceParametersAndSteps(pipeline.getId(), request);
        return toDetail(resolve(request.slug()));
    }

    @Transactional
    public PipelineDetail update(String slug, PipelineUpsertRequest request) {
        Pipeline pipeline = resolve(slug);
        applyFields(pipeline, request, Instant.now());
        pipelineRepository.save(pipeline);
        replaceParametersAndSteps(pipeline.getId(), request);
        return toDetail(resolve(slug));
    }

    @Transactional
    public boolean delete(String slug) {
        return pipelineRepository.findBySlug(slug)
                .map(pipeline -> {
                    pipelineParameterRepository.deleteByPipelineId(pipeline.getId());
                    pipelineStepRepository.deleteByPipelineId(pipeline.getId());
                    pipelineRepository.delete(pipeline);
                    return true;
                })
                .orElse(false);
    }

    Pipeline resolve(String slug) {
        return pipelineRepository.findBySlug(slug)
                .orElseThrow(() -> new PipelineNotFoundException(slug));
    }

    private void applyFields(Pipeline pipeline, PipelineUpsertRequest request, Instant now) {
        pipeline.setName(request.name());
        pipeline.setDescription(request.description());
        pipeline.setProjectScope(request.projectScope());
        pipeline.setUpdatedAt(now);
    }

    private void replaceParametersAndSteps(Long pipelineId, PipelineUpsertRequest request) {
        pipelineParameterRepository.deleteByPipelineId(pipelineId);
        int paramIndex = 0;
        for (PipelineUpsertRequest.ParameterRequest parameterRequest : request.parameters()) {
            PipelineParameter parameter = new PipelineParameter();
            parameter.setPipelineId(pipelineId);
            parameter.setName(parameterRequest.name());
            parameter.setLabel(parameterRequest.label());
            parameter.setType(parameterRequest.type());
            parameter.setRequired(parameterRequest.required());
            parameter.setDefaultValue(parameterRequest.defaultValue());
            parameter.setOrderIndex(paramIndex++);
            pipelineParameterRepository.save(parameter);
        }
        pipelineStepRepository.deleteByPipelineId(pipelineId);
        int stepIndex = 0;
        for (PipelineUpsertRequest.StepRequest stepRequest : request.steps()) {
            PipelineStep step = new PipelineStep();
            step.setPipelineId(pipelineId);
            step.setOrderIndex(stepIndex++);
            step.setTitle(stepRequest.title());
            step.setContentType(stepRequest.contentType());
            step.setPromptText(stepRequest.promptText());
            step.setAssetId(stepRequest.assetId());
            step.setReferenceAssetId(stepRequest.referenceAssetId());
            pipelineStepRepository.save(step);
        }
    }

    private PipelineSummary toSummary(Pipeline pipeline) {
        int parameterCount = pipelineParameterRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).size();
        int stepCount = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).size();
        return new PipelineSummary(pipeline.getId(), pipeline.getSlug(), pipeline.getName(), pipeline.getDescription(),
                pipeline.getProjectScope(), parameterCount, stepCount, pipeline.getCreatedBy(), pipeline.getUpdatedAt());
    }

    private PipelineDetail toDetail(Pipeline pipeline) {
        List<PipelineDetail.PipelineParameterView> parameters = pipelineParameterRepository
                .findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).stream()
                .map(p -> new PipelineDetail.PipelineParameterView(p.getId(), p.getName(), p.getLabel(), p.getType(), p.isRequired(), p.getDefaultValue(), p.getOrderIndex()))
                .toList();
        List<PipelineDetail.PipelineStepView> steps = pipelineStepRepository
                .findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).stream()
                .map(s -> new PipelineDetail.PipelineStepView(s.getId(), s.getOrderIndex(), s.getTitle(), s.getContentType(), s.getPromptText(), s.getAssetId(), s.getReferenceAssetId()))
                .toList();
        return new PipelineDetail(pipeline.getId(), pipeline.getSlug(), pipeline.getName(), pipeline.getDescription(),
                pipeline.getProjectScope(), parameters, steps, pipeline.getCreatedBy(), pipeline.getCreatedAt(), pipeline.getUpdatedAt());
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineServiceTest"`
Expected: PASS (5 tests)

- [ ] **Step 9: Write the controller**

```java
package ru.iuribabalin.memorymcp.ui;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.PipelineDetail;
import ru.iuribabalin.memorymcp.dto.PipelineSummary;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.service.PipelineNotFoundException;
import ru.iuribabalin.memorymcp.service.PipelineService;

import java.util.List;

@RestController
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @GetMapping("/api/pipelines")
    public List<PipelineSummary> list(@RequestParam String projectScope) {
        return pipelineService.list(projectScope);
    }

    @GetMapping("/api/pipelines/{slug}")
    public PipelineDetail get(@PathVariable String slug) {
        return pipelineService.get(slug);
    }

    @PostMapping("/api/pipelines")
    public PipelineDetail create(@RequestBody PipelineUpsertRequest request) {
        return pipelineService.create(request, null);
    }

    @PutMapping("/api/pipelines/{slug}")
    public PipelineDetail update(@PathVariable String slug, @RequestBody PipelineUpsertRequest request) {
        return pipelineService.update(slug, request);
    }

    @DeleteMapping("/api/pipelines/{slug}")
    public ResponseEntity<Void> delete(@PathVariable String slug) {
        if (!pipelineService.delete(slug)) {
            throw new PipelineNotFoundException(slug);
        }
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 10: Add the exception handler entries**

In `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`:

```java
import ru.iuribabalin.memorymcp.service.PipelineNotFoundException;
import ru.iuribabalin.memorymcp.service.PipelineSlugTakenException;
```

```java
    @ExceptionHandler(PipelineNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePipelineNotFound(PipelineNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(PipelineSlugTakenException.class)
    public ResponseEntity<Map<String, String>> handlePipelineSlugTaken(PipelineSlugTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }
```

- [ ] **Step 11: Write the controller test**

```java
package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.iuribabalin.memorymcp.dto.PipelineSummary;
import ru.iuribabalin.memorymcp.service.PipelineNotFoundException;
import ru.iuribabalin.memorymcp.service.PipelineService;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PipelineControllerTest {

    @Mock
    private PipelineService pipelineService;

    @InjectMocks
    private PipelineController pipelineController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(pipelineController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsPipelinesForAProject() throws Exception {
        when(pipelineService.list("memory-mcp"))
                .thenReturn(List.of(new PipelineSummary(1L, "config-diff", "Config diff", "desc", "memory-mcp", 1, 2, "Tester", Instant.now())));

        mockMvc.perform(get("/api/pipelines").param("projectScope", "memory-mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("config-diff"));
    }

    @Test
    void deleteReturns404WhenPipelineIsMissing() throws Exception {
        when(pipelineService.delete("missing")).thenThrow(new PipelineNotFoundException("missing"));

        mockMvc.perform(delete("/api/pipelines/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturnsNoContentOnSuccess() throws Exception {
        when(pipelineService.delete("config-diff")).thenReturn(true);

        mockMvc.perform(delete("/api/pipelines/config-diff"))
                .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 12: Run all tests to verify they pass**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineServiceTest" --tests "ru.iuribabalin.memorymcp.ui.PipelineControllerTest"`
Expected: PASS (8 tests total)

- [ ] **Step 13: Commit**

```bash
git add src/main/resources/db/migration/V11__add_pipelines.sql src/main/java/ru/iuribabalin/memorymcp/entity/Pipeline.java src/main/java/ru/iuribabalin/memorymcp/entity/PipelineParameter.java src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStep.java src/main/java/ru/iuribabalin/memorymcp/repository/PipelineRepository.java src/main/java/ru/iuribabalin/memorymcp/repository/PipelineParameterRepository.java src/main/java/ru/iuribabalin/memorymcp/repository/PipelineStepRepository.java src/main/java/ru/iuribabalin/memorymcp/dto/PipelineSummary.java src/main/java/ru/iuribabalin/memorymcp/dto/PipelineDetail.java src/main/java/ru/iuribabalin/memorymcp/dto/PipelineUpsertRequest.java src/main/java/ru/iuribabalin/memorymcp/service/PipelineNotFoundException.java src/main/java/ru/iuribabalin/memorymcp/service/PipelineSlugTakenException.java src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java src/main/java/ru/iuribabalin/memorymcp/ui/PipelineController.java src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java src/test/java/ru/iuribabalin/memorymcp/ui/PipelineControllerTest.java
git commit -m "feat: add pipeline definition CRUD (parameters + linear steps)"
```

---

## Task 4: Pipeline runs (start/step-update/complete/get) + execution view

**Files:**
- Create: `src/main/resources/db/migration/V12__add_pipeline_runs.sql`
- Create: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineRun.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineRunStep.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/repository/PipelineRunRepository.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/repository/PipelineRunStepRepository.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineRunSummary.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineRunDetail.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineExecutionDetail.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunNotFoundException.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunStepNotFoundException.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineInvalidParametersException.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunService.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java` (add `getForExecution`, `validateParameters`; inject `PipelineAssetService`)
- Create: `src/main/java/ru/iuribabalin/memorymcp/ui/PipelineRunController.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/ui/PipelineRunControllerTest.java`

**Interfaces:**
- Consumes: `PipelineService.resolve` (Task 3, now package-visible within `service`), `PipelineRepository`/`PipelineParameterRepository`/`PipelineStepRepository` (Task 3), `PipelineAssetService.readAsText` (Task 2).
- Produces: `PipelineRunService.start(String slug, String parametersJson, String startedBy): PipelineRunDetail`, `.updateStep(Long runId, int orderIndex, PipelineRunStep.Status, String note): PipelineRunDetail`, `.complete(Long runId, PipelineRun.Status): PipelineRunDetail`, `.get(Long runId): PipelineRunDetail`, `.listByPipeline(String slug): List<PipelineRunSummary>`. `PipelineService.getForExecution(String slug): PipelineExecutionDetail`, `.validateParameters(String slug, String parametersJson): void` (throws `PipelineInvalidParametersException`). Task 5 consumes all of these.

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE pipeline_runs (
    id               BIGSERIAL PRIMARY KEY,
    pipeline_id      BIGINT NOT NULL REFERENCES pipelines (id) ON DELETE CASCADE,
    status           VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','DONE','FAILED','ABORTED')),
    parameters_json  TEXT,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    started_by       VARCHAR(300)
);

CREATE INDEX idx_pipeline_runs_pipeline_id ON pipeline_runs (pipeline_id);

CREATE TABLE pipeline_run_steps (
    id                 BIGSERIAL PRIMARY KEY,
    run_id             BIGINT NOT NULL REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    pipeline_step_id   BIGINT REFERENCES pipeline_steps (id) ON DELETE SET NULL,
    order_index        INTEGER NOT NULL,
    title              VARCHAR(255) NOT NULL,
    content_type       VARCHAR(20) NOT NULL CHECK (content_type IN ('PROMPT','MD_FILE')),
    status             VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','RUNNING','DONE','FAILED','SKIPPED')),
    note               TEXT,
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ
);

CREATE INDEX idx_pipeline_run_steps_run_id ON pipeline_run_steps (run_id);
```

- [ ] **Step 2: Write the entities**

```java
package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pipeline_runs")
public class PipelineRun {

    public enum Status { RUNNING, DONE, FAILED, ABORTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_id", nullable = false)
    private Long pipelineId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "parameters_json", columnDefinition = "text")
    private String parametersJson;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "started_by", length = 300)
    private String startedBy;

    public Long getId() {
        return id;
    }

    public Long getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(Long pipelineId) {
        this.pipelineId = pipelineId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    public void setParametersJson(String parametersJson) {
        this.parametersJson = parametersJson;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getStartedBy() {
        return startedBy;
    }

    public void setStartedBy(String startedBy) {
        this.startedBy = startedBy;
    }
}
```

```java
package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pipeline_run_steps")
public class PipelineRunStep {

    public enum Status { PENDING, RUNNING, DONE, FAILED, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "pipeline_step_id")
    private Long pipelineStepId;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    private PipelineStep.ContentType contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public Long getId() {
        return id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public Long getPipelineStepId() {
        return pipelineStepId;
    }

    public void setPipelineStepId(Long pipelineStepId) {
        this.pipelineStepId = pipelineStepId;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public PipelineStep.ContentType getContentType() {
        return contentType;
    }

    public void setContentType(PipelineStep.ContentType contentType) {
        this.contentType = contentType;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
```

- [ ] **Step 3: Write the repositories**

```java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineRun;

import java.util.List;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, Long> {
    List<PipelineRun> findByPipelineIdOrderByStartedAtDesc(Long pipelineId);
}
```

```java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;

import java.util.List;
import java.util.Optional;

public interface PipelineRunStepRepository extends JpaRepository<PipelineRunStep, Long> {
    List<PipelineRunStep> findByRunIdOrderByOrderIndexAsc(Long runId);
    Optional<PipelineRunStep> findByRunIdAndOrderIndex(Long runId, int orderIndex);
}
```

- [ ] **Step 4: Write the DTOs and exceptions**

```java
package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineRun;

import java.time.Instant;

public record PipelineRunSummary(
        Long id, Long pipelineId, String pipelineSlug, PipelineRun.Status status,
        Instant startedAt, Instant finishedAt, String startedBy) {
}
```

```java
package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.time.Instant;
import java.util.List;

public record PipelineRunDetail(
        Long id, Long pipelineId, String pipelineSlug, PipelineRun.Status status,
        String parametersJson, Instant startedAt, Instant finishedAt, String startedBy,
        List<PipelineRunStepView> steps) {

    public record PipelineRunStepView(
            Long id, int orderIndex, String title, PipelineStep.ContentType contentType,
            PipelineRunStep.Status status, String note, Instant startedAt, Instant finishedAt) {
    }
}
```

```java
package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineParameter;

import java.util.List;

public record PipelineExecutionDetail(
        String slug, String name, String description,
        List<ParameterView> parameters, List<StepView> steps) {

    public record ParameterView(String name, String label, PipelineParameter.Type type, boolean required, String defaultValue) {
    }

    public record StepView(int orderIndex, String title, String instructionText, String referenceText) {
    }
}
```

```java
package ru.iuribabalin.memorymcp.service;

public class PipelineRunNotFoundException extends RuntimeException {
    public PipelineRunNotFoundException(Long runId) {
        super("No pipeline run with id " + runId);
    }
}
```

```java
package ru.iuribabalin.memorymcp.service;

public class PipelineRunStepNotFoundException extends RuntimeException {
    public PipelineRunStepNotFoundException(Long runId, int orderIndex) {
        super("Pipeline run " + runId + " has no step at index " + orderIndex);
    }
}
```

```java
package ru.iuribabalin.memorymcp.service;

public class PipelineInvalidParametersException extends RuntimeException {
    public PipelineInvalidParametersException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Write the failing service test**

```java
package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PipelineRunServiceTest {

    @Autowired
    private PipelineService pipelineService;

    @Autowired
    private PipelineRunService pipelineRunService;

    private void createSamplePipeline(String slug) {
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Config diff", "desc", "pipeline-run-svc-test-project",
                List.of(new PipelineUpsertRequest.ParameterRequest("folder", "Folder", PipelineParameter.Type.STRING, true, null)),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Check history", PipelineStep.ContentType.PROMPT, "Diff {{folder}}", null, null),
                        new PipelineUpsertRequest.StepRequest("Save report", PipelineStep.ContentType.PROMPT, "Save it", null, null))
        ), "Tester");
    }

    @Test
    void startSnapshotsStepsAsPending() {
        createSamplePipeline("run-test-1");

        PipelineRunDetail run = pipelineRunService.start("run-test-1", "{\"folder\":\"src/config\"}", "Tester");

        assertThat(run.status()).isEqualTo(PipelineRun.Status.RUNNING);
        assertThat(run.steps()).hasSize(2);
        assertThat(run.steps()).allMatch(step -> step.status() == PipelineRunStep.Status.PENDING);
    }

    @Test
    void updateStepMovesItToDoneAndStampsFinishedAt() {
        createSamplePipeline("run-test-2");
        PipelineRunDetail run = pipelineRunService.start("run-test-2", "{\"folder\":\"src\"}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "diffed fine");

        assertThat(updated.steps().get(0).status()).isEqualTo(PipelineRunStep.Status.DONE);
        assertThat(updated.steps().get(0).note()).isEqualTo("diffed fine");
        assertThat(updated.steps().get(0).finishedAt()).isNotNull();
    }

    @Test
    void completeSetsFinalStatusAndFinishedAt() {
        createSamplePipeline("run-test-3");
        PipelineRunDetail run = pipelineRunService.start("run-test-3", "{\"folder\":\"src\"}", "Tester");

        PipelineRunDetail completed = pipelineRunService.complete(run.id(), PipelineRun.Status.DONE);

        assertThat(completed.status()).isEqualTo(PipelineRun.Status.DONE);
        assertThat(completed.finishedAt()).isNotNull();
    }

    @Test
    void listByPipelineReturnsRunsNewestFirst() {
        createSamplePipeline("run-test-4");
        pipelineRunService.start("run-test-4", "{\"folder\":\"a\"}", "Tester");
        pipelineRunService.start("run-test-4", "{\"folder\":\"b\"}", "Tester");

        assertThat(pipelineRunService.listByPipeline("run-test-4")).hasSize(2);
    }

    @Test
    void validateParametersThrowsWhenARequiredParameterIsMissing() {
        createSamplePipeline("run-test-5");

        assertThatThrownBy(() -> pipelineService.validateParameters("run-test-5", "{}"))
                .isInstanceOf(PipelineInvalidParametersException.class)
                .hasMessageContaining("folder");
    }

    @Test
    void getForExecutionInterpolatesNothingButReturnsRawInstructionText() {
        createSamplePipeline("run-test-6");

        var execution = pipelineService.getForExecution("run-test-6");

        assertThat(execution.steps()).extracting(step -> step.instructionText())
                .containsExactly("Diff {{folder}}", "Save it");
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineRunServiceTest"`
Expected: FAIL to compile — `PipelineRunService` doesn't exist yet, `PipelineService.validateParameters`/`getForExecution` don't exist yet.

- [ ] **Step 7: Extend `PipelineService` with execution support**

Add this constructor parameter and these two methods to `src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java` (keep every existing method unchanged):

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.iuribabalin.memorymcp.dto.PipelineExecutionDetail;

import java.util.HashSet;
import java.util.Set;
```

```java
    private final PipelineAssetService pipelineAssetService;
    private final ObjectMapper objectMapper;

    public PipelineService(PipelineRepository pipelineRepository,
                            PipelineParameterRepository pipelineParameterRepository,
                            PipelineStepRepository pipelineStepRepository,
                            PipelineAssetService pipelineAssetService,
                            ObjectMapper objectMapper) {
        this.pipelineRepository = pipelineRepository;
        this.pipelineParameterRepository = pipelineParameterRepository;
        this.pipelineStepRepository = pipelineStepRepository;
        this.pipelineAssetService = pipelineAssetService;
        this.objectMapper = objectMapper;
    }
```

```java
    @Transactional(readOnly = true)
    public PipelineExecutionDetail getForExecution(String slug) {
        Pipeline pipeline = resolve(slug);
        List<PipelineExecutionDetail.ParameterView> parameters = pipelineParameterRepository
                .findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).stream()
                .map(p -> new PipelineExecutionDetail.ParameterView(p.getName(), p.getLabel(), p.getType(), p.isRequired(), p.getDefaultValue()))
                .toList();
        List<PipelineExecutionDetail.StepView> steps = pipelineStepRepository
                .findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).stream()
                .map(step -> new PipelineExecutionDetail.StepView(
                        step.getOrderIndex(),
                        step.getTitle(),
                        step.getContentType() == PipelineStep.ContentType.MD_FILE
                                ? pipelineAssetService.readAsText(step.getAssetId())
                                : step.getPromptText(),
                        step.getReferenceAssetId() != null ? pipelineAssetService.readAsText(step.getReferenceAssetId()) : null))
                .toList();
        return new PipelineExecutionDetail(pipeline.getSlug(), pipeline.getName(), pipeline.getDescription(), parameters, steps);
    }

    @Transactional(readOnly = true)
    public void validateParameters(String slug, String parametersJson) {
        Pipeline pipeline = resolve(slug);
        List<PipelineParameter> parameters = pipelineParameterRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId());
        Set<String> provided = new HashSet<>();
        if (parametersJson != null && !parametersJson.isBlank()) {
            JsonNode node;
            try {
                node = objectMapper.readTree(parametersJson);
            } catch (Exception ex) {
                throw new PipelineInvalidParametersException("parametersJson is not valid JSON: " + ex.getMessage());
            }
            node.fieldNames().forEachRemaining(provided::add);
        }
        List<String> missing = parameters.stream()
                .filter(PipelineParameter::isRequired)
                .map(PipelineParameter::getName)
                .filter(name -> !provided.contains(name))
                .toList();
        if (!missing.isEmpty()) {
            throw new PipelineInvalidParametersException("Missing required parameters: " + String.join(", ", missing));
        }
    }
```

- [ ] **Step 8: Write the `PipelineRunService`**

```java
package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.dto.PipelineRunSummary;
import ru.iuribabalin.memorymcp.entity.Pipeline;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import ru.iuribabalin.memorymcp.repository.PipelineRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRunRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRunStepRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRepository;

import java.time.Instant;
import java.util.List;

@Service
public class PipelineRunService {

    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineRunStepRepository pipelineRunStepRepository;
    private final PipelineRepository pipelineRepository;
    private final PipelineStepRepository pipelineStepRepository;

    public PipelineRunService(PipelineRunRepository pipelineRunRepository,
                               PipelineRunStepRepository pipelineRunStepRepository,
                               PipelineRepository pipelineRepository,
                               PipelineStepRepository pipelineStepRepository) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineRunStepRepository = pipelineRunStepRepository;
        this.pipelineRepository = pipelineRepository;
        this.pipelineStepRepository = pipelineStepRepository;
    }

    @Transactional
    public PipelineRunDetail start(String slug, String parametersJson, String startedBy) {
        Pipeline pipeline = pipelineRepository.findBySlug(slug)
                .orElseThrow(() -> new PipelineNotFoundException(slug));
        List<PipelineStep> steps = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId());
        Instant now = Instant.now();
        PipelineRun run = new PipelineRun();
        run.setPipelineId(pipeline.getId());
        run.setStatus(PipelineRun.Status.RUNNING);
        run.setParametersJson(parametersJson);
        run.setStartedAt(now);
        run.setStartedBy(startedBy);
        run = pipelineRunRepository.save(run);
        for (PipelineStep step : steps) {
            PipelineRunStep runStep = new PipelineRunStep();
            runStep.setRunId(run.getId());
            runStep.setPipelineStepId(step.getId());
            runStep.setOrderIndex(step.getOrderIndex());
            runStep.setTitle(step.getTitle());
            runStep.setContentType(step.getContentType());
            runStep.setStatus(PipelineRunStep.Status.PENDING);
            pipelineRunStepRepository.save(runStep);
        }
        return toDetail(run, pipeline.getSlug());
    }

    @Transactional
    public PipelineRunDetail updateStep(Long runId, int orderIndex, PipelineRunStep.Status status, String note) {
        PipelineRun run = resolve(runId);
        PipelineRunStep runStep = pipelineRunStepRepository.findByRunIdAndOrderIndex(runId, orderIndex)
                .orElseThrow(() -> new PipelineRunStepNotFoundException(runId, orderIndex));
        Instant now = Instant.now();
        if (runStep.getStartedAt() == null && status == PipelineRunStep.Status.RUNNING) {
            runStep.setStartedAt(now);
        }
        if (status == PipelineRunStep.Status.DONE || status == PipelineRunStep.Status.FAILED
                || status == PipelineRunStep.Status.SKIPPED) {
            runStep.setFinishedAt(now);
        }
        runStep.setStatus(status);
        runStep.setNote(note);
        pipelineRunStepRepository.save(runStep);
        return toDetail(run, pipelineSlugOf(run));
    }

    @Transactional
    public PipelineRunDetail complete(Long runId, PipelineRun.Status status) {
        PipelineRun run = resolve(runId);
        run.setStatus(status);
        run.setFinishedAt(Instant.now());
        pipelineRunRepository.save(run);
        return toDetail(run, pipelineSlugOf(run));
    }

    @Transactional(readOnly = true)
    public PipelineRunDetail get(Long runId) {
        PipelineRun run = resolve(runId);
        return toDetail(run, pipelineSlugOf(run));
    }

    @Transactional(readOnly = true)
    public List<PipelineRunSummary> listByPipeline(String slug) {
        Pipeline pipeline = pipelineRepository.findBySlug(slug)
                .orElseThrow(() -> new PipelineNotFoundException(slug));
        return pipelineRunRepository.findByPipelineIdOrderByStartedAtDesc(pipeline.getId()).stream()
                .map(run -> toSummary(run, pipeline.getSlug()))
                .toList();
    }

    private PipelineRun resolve(Long runId) {
        return pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new PipelineRunNotFoundException(runId));
    }

    private String pipelineSlugOf(PipelineRun run) {
        return pipelineRepository.findById(run.getPipelineId()).map(Pipeline::getSlug).orElse(null);
    }

    private PipelineRunSummary toSummary(PipelineRun run, String pipelineSlug) {
        return new PipelineRunSummary(run.getId(), run.getPipelineId(), pipelineSlug, run.getStatus(),
                run.getStartedAt(), run.getFinishedAt(), run.getStartedBy());
    }

    private PipelineRunDetail toDetail(PipelineRun run, String pipelineSlug) {
        List<PipelineRunDetail.PipelineRunStepView> steps = pipelineRunStepRepository
                .findByRunIdOrderByOrderIndexAsc(run.getId()).stream()
                .map(s -> new PipelineRunDetail.PipelineRunStepView(s.getId(), s.getOrderIndex(), s.getTitle(),
                        s.getContentType(), s.getStatus(), s.getNote(), s.getStartedAt(), s.getFinishedAt()))
                .toList();
        return new PipelineRunDetail(run.getId(), run.getPipelineId(), pipelineSlug, run.getStatus(),
                run.getParametersJson(), run.getStartedAt(), run.getFinishedAt(), run.getStartedBy(), steps);
    }
}
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineRunServiceTest"`
Expected: PASS (6 tests)

- [ ] **Step 10: Write the controller**

```java
package ru.iuribabalin.memorymcp.ui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.dto.PipelineRunSummary;
import ru.iuribabalin.memorymcp.service.PipelineRunService;

import java.util.List;

@RestController
public class PipelineRunController {

    private final PipelineRunService pipelineRunService;

    public PipelineRunController(PipelineRunService pipelineRunService) {
        this.pipelineRunService = pipelineRunService;
    }

    @GetMapping("/api/pipelines/{slug}/runs")
    public List<PipelineRunSummary> listByPipeline(@PathVariable String slug) {
        return pipelineRunService.listByPipeline(slug);
    }

    @GetMapping("/api/pipeline-runs/{id}")
    public PipelineRunDetail get(@PathVariable Long id) {
        return pipelineRunService.get(id);
    }
}
```

- [ ] **Step 11: Add the exception handler entries**

In `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`:

```java
import ru.iuribabalin.memorymcp.service.PipelineRunNotFoundException;
import ru.iuribabalin.memorymcp.service.PipelineRunStepNotFoundException;
```

```java
    @ExceptionHandler(PipelineRunNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePipelineRunNotFound(PipelineRunNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(PipelineRunStepNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePipelineRunStepNotFound(PipelineRunStepNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }
```

- [ ] **Step 12: Write the controller test**

```java
package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.service.PipelineRunNotFoundException;
import ru.iuribabalin.memorymcp.service.PipelineRunService;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PipelineRunControllerTest {

    @Mock
    private PipelineRunService pipelineRunService;

    @InjectMocks
    private PipelineRunController pipelineRunController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(pipelineRunController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void getReturns404ForUnknownRun() throws Exception {
        when(pipelineRunService.get(99L)).thenThrow(new PipelineRunNotFoundException(99L));

        mockMvc.perform(get("/api/pipeline-runs/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReturnsTheRun() throws Exception {
        when(pipelineRunService.get(1L)).thenReturn(new PipelineRunDetail(
                1L, 1L, "config-diff", PipelineRun.Status.RUNNING, "{}", Instant.now(), null, "Tester", List.of()));

        mockMvc.perform(get("/api/pipeline-runs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelineSlug").value("config-diff"));
    }
}
```

- [ ] **Step 13: Run all tests to verify they pass**

Run: `./gradlew test`
Expected: PASS (full suite, including the new run tests, and Task 1-3 tests still passing)

- [ ] **Step 14: Commit**

```bash
git add src/main/resources/db/migration/V12__add_pipeline_runs.sql src/main/java/ru/iuribabalin/memorymcp/entity/PipelineRun.java src/main/java/ru/iuribabalin/memorymcp/entity/PipelineRunStep.java src/main/java/ru/iuribabalin/memorymcp/repository/PipelineRunRepository.java src/main/java/ru/iuribabalin/memorymcp/repository/PipelineRunStepRepository.java src/main/java/ru/iuribabalin/memorymcp/dto/PipelineRunSummary.java src/main/java/ru/iuribabalin/memorymcp/dto/PipelineRunDetail.java src/main/java/ru/iuribabalin/memorymcp/dto/PipelineExecutionDetail.java src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunNotFoundException.java src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunStepNotFoundException.java src/main/java/ru/iuribabalin/memorymcp/service/PipelineInvalidParametersException.java src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunService.java src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java src/main/java/ru/iuribabalin/memorymcp/ui/PipelineRunController.java src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java src/test/java/ru/iuribabalin/memorymcp/ui/PipelineRunControllerTest.java
git commit -m "feat: add pipeline run lifecycle (start/step-update/complete) and execution view"
```

---

## Task 5: PipelineMcpTools (read/execute-only, flag-gated)

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/UsageEvent.java` (add 3 enum values)
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineFeatureDisabledException.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpTools.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpToolsTest.java`

**Interfaces:**
- Consumes: `PipelineService.list/get/getForExecution/validateParameters` (Task 3/4), `PipelineRunService.start/updateStep/complete/get` (Task 4), `SettingsService.isEnabled/PIPELINES_ENABLED` (Task 1), `UsageEventRecorder.record` (existing).
- Produces: MCP tools `pipeline_list`, `pipeline_get`, `pipeline_run_start`, `pipeline_run_step_update`, `pipeline_run_complete`, `pipeline_run_get` — consumed by the `pipelines` skill (Task 10).

- [ ] **Step 1: Add the new `UsageEvent.Action` values**

Find the `enum Action { ... }` in `src/main/java/ru/iuribabalin/memorymcp/entity/UsageEvent.java` and add three values to the end of the list: `PIPELINE_RUN_START, PIPELINE_RUN_STEP_UPDATE, PIPELINE_RUN_COMPLETE`.

- [ ] **Step 2: Write the exception**

```java
package ru.iuribabalin.memorymcp.service;

public class PipelineFeatureDisabledException extends RuntimeException {
    public PipelineFeatureDisabledException() {
        super("Экспериментальная функция «Пайплайны» выключена. Включите её в Настройках дашборда.");
    }
}
```

- [ ] **Step 3: Write the failing test**

```java
package ru.iuribabalin.memorymcp.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.iuribabalin.memorymcp.dto.PipelineExecutionDetail;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.service.PipelineFeatureDisabledException;
import ru.iuribabalin.memorymcp.service.PipelineRunService;
import ru.iuribabalin.memorymcp.service.PipelineService;
import ru.iuribabalin.memorymcp.service.SettingsService;
import ru.iuribabalin.memorymcp.service.UsageEventRecorder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineMcpToolsTest {

    @Mock
    private PipelineService pipelineService;
    @Mock
    private PipelineRunService pipelineRunService;
    @Mock
    private SettingsService settingsService;
    @Mock
    private UsageEventRecorder usageEventRecorder;

    @InjectMocks
    private PipelineMcpTools pipelineMcpTools;

    @BeforeEach
    void setUp() {
    }

    @Test
    void throwsWhenTheFeatureFlagIsDisabled() {
        when(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).thenReturn(false);

        assertThatThrownBy(() -> pipelineMcpTools.pipelineList("memory-mcp"))
                .isInstanceOf(PipelineFeatureDisabledException.class);
    }

    @Test
    void pipelineGetDelegatesWhenEnabled() {
        when(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).thenReturn(true);
        PipelineExecutionDetail detail = new PipelineExecutionDetail("config-diff", "Config diff", "desc", List.of(), List.of());
        when(pipelineService.getForExecution("config-diff")).thenReturn(detail);

        PipelineExecutionDetail result = pipelineMcpTools.pipelineGet("config-diff");

        assertThat(result).isEqualTo(detail);
    }

    @Test
    void runStartValidatesParametersAndRecordsUsage() {
        when(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).thenReturn(true);
        PipelineRunDetail runDetail = new PipelineRunDetail(1L, 1L, "config-diff", PipelineRun.Status.RUNNING, "{}", Instant.now(), null, null, List.of());
        when(pipelineRunService.start("config-diff", "{}", null)).thenReturn(runDetail);

        PipelineRunDetail result = pipelineMcpTools.pipelineRunStart("config-diff", "{}");

        assertThat(result).isEqualTo(runDetail);
        verify(pipelineService).validateParameters("config-diff", "{}");
        verify(usageEventRecorder).record(ru.iuribabalin.memorymcp.entity.UsageEvent.Action.PIPELINE_RUN_START, "config-diff", null, null, null);
    }

    @Test
    void runStepUpdateDelegatesAndRecordsUsage() {
        when(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).thenReturn(true);
        PipelineRunDetail runDetail = new PipelineRunDetail(1L, 1L, "config-diff", PipelineRun.Status.RUNNING, "{}", Instant.now(), null, null, List.of());
        when(pipelineRunService.updateStep(1L, 0, PipelineRunStep.Status.DONE, "ok")).thenReturn(runDetail);

        PipelineRunDetail result = pipelineMcpTools.pipelineRunStepUpdate(1L, 0, PipelineRunStep.Status.DONE, "ok");

        assertThat(result).isEqualTo(runDetail);
        verify(usageEventRecorder).record(ru.iuribabalin.memorymcp.entity.UsageEvent.Action.PIPELINE_RUN_STEP_UPDATE, "1", null, null, null);
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.mcp.PipelineMcpToolsTest"`
Expected: FAIL to compile — `PipelineMcpTools` doesn't exist yet.

- [ ] **Step 5: Write `PipelineMcpTools`**

```java
package ru.iuribabalin.memorymcp.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.iuribabalin.memorymcp.dto.PipelineExecutionDetail;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.dto.PipelineSummary;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.UsageEvent;
import ru.iuribabalin.memorymcp.service.PipelineFeatureDisabledException;
import ru.iuribabalin.memorymcp.service.PipelineRunService;
import ru.iuribabalin.memorymcp.service.PipelineService;
import ru.iuribabalin.memorymcp.service.SettingsService;
import ru.iuribabalin.memorymcp.service.UsageEventRecorder;

import java.util.List;

@Component
public class PipelineMcpTools {

    private final PipelineService pipelineService;
    private final PipelineRunService pipelineRunService;
    private final SettingsService settingsService;
    private final UsageEventRecorder usageEventRecorder;

    public PipelineMcpTools(PipelineService pipelineService, PipelineRunService pipelineRunService,
                             SettingsService settingsService, UsageEventRecorder usageEventRecorder) {
        this.pipelineService = pipelineService;
        this.pipelineRunService = pipelineRunService;
        this.settingsService = settingsService;
        this.usageEventRecorder = usageEventRecorder;
    }

    @McpTool(name = "pipeline_list",
            description = "List hand-authored pipelines available for a project - each is a named, linear sequence " +
                    "of steps built in the memory-mcp dashboard. Call this to resolve a pipeline name the user " +
                    "mentioned into its exact slug before pipeline_get/pipeline_run_start. Disabled unless the " +
                    "'Pipelines' experimental feature is turned on in dashboard Settings.")
    public List<PipelineSummary> pipelineList(
            @McpToolParam(description = "Project identifier, auto-derived from the git repo name", required = true) String projectScope) {
        requireEnabled();
        return pipelineService.list(projectScope);
    }

    @McpTool(name = "pipeline_get",
            description = "Fetch a pipeline's full definition - its ordered steps and parameters. Uploaded .md step " +
                    "content and any optional reference attachment are inlined as plain text, no separate download " +
                    "needed. Call before pipeline_run_start so you know what parameters to ask the user for.")
    public PipelineExecutionDetail pipelineGet(
            @McpToolParam(description = "The pipeline's slug, from pipeline_list", required = true) String slug) {
        requireEnabled();
        return pipelineService.getForExecution(slug);
    }

    @McpTool(name = "pipeline_run_start",
            description = "Start a run of a pipeline: validates required parameters are present, snapshots its " +
                    "current steps, and returns a runId plus the ordered step list to work through. Call " +
                    "pipeline_run_step_update after finishing each step, in order, and pipeline_run_complete once " +
                    "every step is done or the run is being abandoned.")
    public PipelineRunDetail pipelineRunStart(
            @McpToolParam(description = "The pipeline's slug", required = true) String slug,
            @McpToolParam(description = "JSON object of parameter values keyed by parameter name, e.g. {\"folder\": \"src/config\"} - include every required parameter", required = false) String parametersJson) {
        requireEnabled();
        pipelineService.validateParameters(slug, parametersJson);
        PipelineRunDetail run = pipelineRunService.start(slug, parametersJson, null);
        usageEventRecorder.record(UsageEvent.Action.PIPELINE_RUN_START, slug, null, null, null);
        return run;
    }

    @McpTool(name = "pipeline_run_step_update",
            description = "Report the outcome of one pipeline run step after doing its work: RUNNING when you start " +
                    "it, then DONE or FAILED when you finish, or SKIPPED if the user told you to skip it. Include a " +
                    "short note describing what you did or why it failed. On FAILED, stop and ask the user how to " +
                    "proceed before calling this again - do not silently continue to the next step.")
    public PipelineRunDetail pipelineRunStepUpdate(
            @McpToolParam(description = "The run id, from pipeline_run_start", required = true) Long runId,
            @McpToolParam(description = "0-based index of the step in the run's step list", required = true) Integer orderIndex,
            @McpToolParam(description = "New status: RUNNING, DONE, FAILED, or SKIPPED", required = true) PipelineRunStep.Status status,
            @McpToolParam(description = "Short summary of what happened for this step", required = false) String note) {
        requireEnabled();
        PipelineRunDetail run = pipelineRunService.updateStep(runId, orderIndex, status, note);
        usageEventRecorder.record(UsageEvent.Action.PIPELINE_RUN_STEP_UPDATE, String.valueOf(runId), null, null, null);
        return run;
    }

    @McpTool(name = "pipeline_run_complete",
            description = "Finish a pipeline run once every step is done, or to mark it FAILED/ABORTED if it's being " +
                    "given up on partway through.")
    public PipelineRunDetail pipelineRunComplete(
            @McpToolParam(description = "The run id", required = true) Long runId,
            @McpToolParam(description = "Final status: DONE, FAILED, or ABORTED", required = true) PipelineRun.Status status) {
        requireEnabled();
        PipelineRunDetail run = pipelineRunService.complete(runId, status);
        usageEventRecorder.record(UsageEvent.Action.PIPELINE_RUN_COMPLETE, String.valueOf(runId), null, null, null);
        return run;
    }

    @McpTool(name = "pipeline_run_get",
            description = "Fetch a pipeline run's current state - use this to resume a run a previous session left " +
                    "mid-way, to see which steps are already done.")
    public PipelineRunDetail pipelineRunGet(
            @McpToolParam(description = "The run id", required = true) Long runId) {
        requireEnabled();
        return pipelineRunService.get(runId);
    }

    private void requireEnabled() {
        if (!settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)) {
            throw new PipelineFeatureDisabledException();
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.mcp.PipelineMcpToolsTest"`
Expected: PASS (4 tests)

- [ ] **Step 7: Run the full backend suite**

Run: `docker compose up -d && ./gradlew test`
Expected: PASS (entire suite green)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/entity/UsageEvent.java src/main/java/ru/iuribabalin/memorymcp/service/PipelineFeatureDisabledException.java src/main/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpTools.java src/test/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpToolsTest.java
git commit -m "feat: add flag-gated MCP tools to run pipelines from Claude Code"
```

---

## Task 6: Frontend — types, API client, mutating-request helpers

**Files:**
- Modify: `ui/src/api/types.ts`
- Modify: `ui/src/api/client.ts`

**Interfaces:**
- Produces: TS types `SettingSummary`, `PipelineParameterType`, `PipelineStepContentType`, `PipelineRunStatus`, `PipelineRunStepStatus`, `PipelineAssetSummary`, `PipelineParameterView`, `PipelineStepView`, `PipelineSummary`, `PipelineDetail`, `PipelineUpsertRequest` (+ nested `PipelineUpsertParameter`/`PipelineUpsertStep`), `PipelineRunSummary`, `PipelineRunDetail`, `PipelineRunStepView`. Client functions: `fetchSettings`, `updateSetting`, `fetchPipelines`, `fetchPipeline`, `createPipeline`, `updatePipeline`, `deletePipeline`, `uploadPipelineAsset`, `fetchPipelineRuns`, `fetchPipelineRun`. Tasks 7-9 consume all of these.

- [ ] **Step 1: Add the new types to `ui/src/api/types.ts`**

Append to the end of the file:

```ts
export interface SettingSummary {
  key: string
  value: string
  updatedAt: string
}

export type PipelineParameterType = 'STRING' | 'NUMBER' | 'BOOLEAN'
export type PipelineStepContentType = 'PROMPT' | 'MD_FILE'
export type PipelineRunStatus = 'RUNNING' | 'DONE' | 'FAILED' | 'ABORTED'
export type PipelineRunStepStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'

export interface PipelineAssetSummary {
  id: number
  filename: string
  contentType: string
  sizeBytes: number
  createdAt: string
}

export interface PipelineParameterView {
  id: number
  name: string
  label: string
  type: PipelineParameterType
  required: boolean
  defaultValue: string | null
  orderIndex: number
}

export interface PipelineStepView {
  id: number
  orderIndex: number
  title: string
  contentType: PipelineStepContentType
  promptText: string | null
  assetId: number | null
  referenceAssetId: number | null
}

export interface PipelineSummary {
  id: number
  slug: string
  name: string
  description: string | null
  projectScope: string | null
  parameterCount: number
  stepCount: number
  createdBy: string | null
  updatedAt: string
}

export interface PipelineDetail {
  id: number
  slug: string
  name: string
  description: string | null
  projectScope: string | null
  parameters: PipelineParameterView[]
  steps: PipelineStepView[]
  createdBy: string | null
  createdAt: string
  updatedAt: string
}

export interface PipelineUpsertParameter {
  name: string
  label: string
  type: PipelineParameterType
  required: boolean
  defaultValue: string | null
}

export interface PipelineUpsertStep {
  title: string
  contentType: PipelineStepContentType
  promptText: string | null
  assetId: number | null
  referenceAssetId: number | null
}

export interface PipelineUpsertRequest {
  slug: string
  name: string
  description: string | null
  projectScope: string | null
  parameters: PipelineUpsertParameter[]
  steps: PipelineUpsertStep[]
}

export interface PipelineRunSummary {
  id: number
  pipelineId: number
  pipelineSlug: string
  status: PipelineRunStatus
  startedAt: string
  finishedAt: string | null
  startedBy: string | null
}

export interface PipelineRunStepView {
  id: number
  orderIndex: number
  title: string
  contentType: PipelineStepContentType
  status: PipelineRunStepStatus
  note: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface PipelineRunDetail {
  id: number
  pipelineId: number
  pipelineSlug: string
  status: PipelineRunStatus
  parametersJson: string | null
  startedAt: string
  finishedAt: string | null
  startedBy: string | null
  steps: PipelineRunStepView[]
}
```

- [ ] **Step 2: Add mutating-request helpers and the new endpoints to `ui/src/api/client.ts`**

Add these imports to the existing `import type { ... } from './types'` block: `PipelineAssetSummary`, `PipelineDetail`, `PipelineRunDetail`, `PipelineRunSummary`, `PipelineSummary`, `PipelineUpsertRequest`, `SettingSummary`.

Add two new private helpers right after the existing `deleteRequest` function:

```ts
async function postJson<T>(path: string, body: unknown): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'Cannot reach the memory-mcp server')
  }
  if (!response.ok) {
    const errorBody = await response.json().catch(() => null)
    const message = (errorBody as { error?: string } | null)?.error ?? `HTTP ${response.status}`
    throw new ApiError(response.status, message)
  }
  bumpDataVersion()
  return response.json() as Promise<T>
}

async function putJson<T>(path: string, body: unknown): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'Cannot reach the memory-mcp server')
  }
  if (!response.ok) {
    const errorBody = await response.json().catch(() => null)
    const message = (errorBody as { error?: string } | null)?.error ?? `HTTP ${response.status}`
    throw new ApiError(response.status, message)
  }
  bumpDataVersion()
  return response.json() as Promise<T>
}
```

Append at the end of the file:

```ts
export function fetchSettings(): Promise<SettingSummary[]> {
  return getJson('/api/settings')
}

export function updateSetting(key: string, value: string): Promise<SettingSummary> {
  return putJson(`/api/settings/${encodeURIComponent(key)}`, { value })
}

export function fetchPipelines(projectScope: string): Promise<PipelineSummary[]> {
  return getJson('/api/pipelines', { projectScope })
}

export function fetchPipeline(slug: string): Promise<PipelineDetail> {
  return getJson(`/api/pipelines/${encodeURIComponent(slug)}`)
}

export function createPipeline(request: PipelineUpsertRequest): Promise<PipelineDetail> {
  return postJson('/api/pipelines', request)
}

export function updatePipeline(slug: string, request: PipelineUpsertRequest): Promise<PipelineDetail> {
  return putJson(`/api/pipelines/${encodeURIComponent(slug)}`, request)
}

export function deletePipeline(slug: string): Promise<void> {
  return deleteRequest(`/api/pipelines/${encodeURIComponent(slug)}`)
}

export async function uploadPipelineAsset(file: File): Promise<PipelineAssetSummary> {
  const formData = new FormData()
  formData.append('file', file)
  let response: Response
  try {
    response = await fetch('/api/pipeline-assets', { method: 'POST', body: formData })
  } catch {
    throw new ApiError(0, 'Cannot reach the memory-mcp server')
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    const message = (body as { error?: string } | null)?.error ?? `HTTP ${response.status}`
    throw new ApiError(response.status, message)
  }
  return response.json() as Promise<PipelineAssetSummary>
}

export function fetchPipelineRuns(slug: string): Promise<PipelineRunSummary[]> {
  return getJson(`/api/pipelines/${encodeURIComponent(slug)}/runs`)
}

export function fetchPipelineRun(id: number): Promise<PipelineRunDetail> {
  return getJson(`/api/pipeline-runs/${id}`)
}
```

- [ ] **Step 3: Type-check**

Run: `cd ui && npm run type-check`
Expected: no errors

- [ ] **Step 4: Commit**

```bash
git add ui/src/api/types.ts ui/src/api/client.ts
git commit -m "feat(ui): add pipelines/settings API types and client functions"
```

---

## Task 7: Frontend — Settings page, nav wiring, hard-refresh route

**Files:**
- Create: `ui/src/views/SettingsView.vue`
- Modify: `ui/src/router/index.ts`
- Modify: `ui/src/components/AppSidebar.vue`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/ui/SpaForwardController.java`

**Interfaces:**
- Consumes: `fetchSettings`, `updateSetting` (Task 6).
- Produces: route name `settings`; `AppSidebar.vue` exposes a reactive `pipelinesEnabled` computed that Task 8/9's nav link (added in Task 8) will read the same way — Task 8 re-fetches settings itself via the same `useAsyncData(fetchSettings, [dataVersion])` pattern shown here, so no shared export is needed.

- [ ] **Step 1: Add the `/settings` route**

In `ui/src/router/index.ts`, add this route object right after the `setup` route:

```ts
    { path: '/settings', name: 'settings', component: () => import('@/views/SettingsView.vue') },
```

- [ ] **Step 2: Register `/settings` for hard-refresh forwarding**

In `src/main/java/ru/iuribabalin/memorymcp/ui/SpaForwardController.java`, change:

```java
    @GetMapping({"/setup", "/stats", "/p/**"})
```

to:

```java
    @GetMapping({"/setup", "/stats", "/settings", "/p/**"})
```

- [ ] **Step 3: Write `SettingsView.vue`**

```vue
<script setup lang="ts">
import { fetchSettings, updateSetting } from '@/api/client'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'

interface FlagDescriptor {
  key: string
  title: string
  hint: string
}

const FLAGS: FlagDescriptor[] = [
  {
    key: 'feature.pipelines.enabled',
    title: 'Пайплайны',
    hint: 'Ручное построение и запуск именованных цепочек шагов из чата Claude Code.',
  },
]

const { data: settings, error, loading, reload } = useAsyncData(fetchSettings)

function isOn(key: string): boolean {
  return settings.value?.find((setting) => setting.key === key)?.value === 'true'
}

async function toggle(key: string) {
  await updateSetting(key, isOn(key) ? 'false' : 'true')
  await reload()
}
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Settings"
      title="Экспериментальные функции"
      subtitle="Выключены по умолчанию — включайте по одной, когда готовы попробовать."
    />

    <ErrorState v-if="error" :message="error" @retry="reload" />
    <SkeletonRows v-else-if="loading" :rows="1" />

    <ul v-else class="space-y-3">
      <li
        v-for="flag in FLAGS"
        :key="flag.key"
        class="flex items-center justify-between gap-4 rounded-2xl border border-border bg-panel p-5"
      >
        <div class="min-w-0">
          <h2 class="text-[14.5px] font-semibold tracking-tight text-content">{{ flag.title }}</h2>
          <p class="mt-1 text-[13px] text-muted">{{ flag.hint }}</p>
        </div>
        <button
          type="button"
          role="switch"
          :aria-checked="isOn(flag.key)"
          class="relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition"
          :class="isOn(flag.key) ? 'bg-accent' : 'bg-border-strong'"
          @click="toggle(flag.key)"
        >
          <span
            class="inline-block size-4 transform rounded-full bg-white transition"
            :class="isOn(flag.key) ? 'translate-x-6' : 'translate-x-1'"
          />
        </button>
      </li>
    </ul>
  </div>
</template>
```

- [ ] **Step 4: Add the sidebar nav item**

In `ui/src/components/AppSidebar.vue`, add a new `RouterLink` right after the "Setup" one (inside the same `<nav class="px-3 py-2">` block):

```vue
      <RouterLink
        :to="{ name: 'settings' }"
        class="flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13px] font-medium text-muted transition hover:bg-elevated hover:text-content"
        active-class="!bg-accent-soft !text-accent"
      >
        <AppIcon name="cog" class="size-4" />
        Settings
      </RouterLink>
```

- [ ] **Step 5: Type-check**

Run: `cd ui && npm run type-check`
Expected: no errors

- [ ] **Step 6: Rebuild the backend and manually verify**

Run: `docker compose up -d && ./gradlew bootRun` (in one terminal), then in a browser open `http://localhost:8080/settings`, confirm the "Пайплайны" toggle renders off, click it, refresh the page, confirm it's still on (persisted). Stop `bootRun` when done (Ctrl+C).

- [ ] **Step 7: Commit**

```bash
git add ui/src/views/SettingsView.vue ui/src/router/index.ts ui/src/components/AppSidebar.vue src/main/java/ru/iuribabalin/memorymcp/ui/SpaForwardController.java
git commit -m "feat(ui): add Settings page with the pipelines experimental flag"
```

---

## Task 8: Frontend — Pipelines list + builder

**Files:**
- Create: `ui/src/views/PipelinesView.vue`
- Create: `ui/src/views/PipelineBuilderView.vue`
- Modify: `ui/src/router/index.ts`

**Interfaces:**
- Consumes: `fetchSettings`, `fetchPipelines`, `fetchPipeline`, `createPipeline`, `updatePipeline`, `uploadPipelineAsset` (Task 6); `PageHeader`, `ErrorState`, `SkeletonRows`, `EmptyState`, `ConfirmDialog`, `AppIcon` (existing components).
- Produces: routes `pipelines` (`/p/:project/pipelines`), `pipeline-new` (`/p/:project/pipelines/new`), `pipeline-edit` (`/p/:project/pipelines/:slug/edit`). Task 9 adds the `pipeline` (detail) and `pipeline-run` routes alongside these.

- [ ] **Step 1: Add the routes**

In `ui/src/router/index.ts`, add these three route objects after the `project-graph` route:

```ts
    {
      path: '/p/:project/pipelines',
      name: 'pipelines',
      component: () => import('@/views/PipelinesView.vue'),
      props: true,
    },
    {
      path: '/p/:project/pipelines/new',
      name: 'pipeline-new',
      component: () => import('@/views/PipelineBuilderView.vue'),
      props: true,
    },
    {
      path: '/p/:project/pipelines/:slug/edit',
      name: 'pipeline-edit',
      component: () => import('@/views/PipelineBuilderView.vue'),
      props: true,
    },
```

- [ ] **Step 2: Write `PipelinesView.vue`**

```vue
<script setup lang="ts">
import { toRef } from 'vue'

import { fetchPipelines, fetchSettings } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { dataVersion } from '@/lib/dataVersion'

const props = defineProps<{ project: string }>()
const project = toRef(props, 'project')

const { data: settings } = useAsyncData(fetchSettings, [dataVersion])
const { data: pipelines, error, loading, reload } = useAsyncData(
  () => fetchPipelines(project.value),
  [project],
)
</script>

<template>
  <div>
    <PageHeader eyebrow="Pipelines" title="Пайплайны" subtitle="Именованные цепочки шагов, собранные вручную.">
      <template #actions>
        <RouterLink
          :to="{ name: 'pipeline-new', params: { project } }"
          class="inline-flex items-center gap-2 rounded-lg bg-accent px-3 py-2 text-[13px] font-medium text-accent-fg transition hover:bg-accent-hover"
        >
          <AppIcon name="task" class="size-4" />
          Новый пайплайн
        </RouterLink>
      </template>
    </PageHeader>

    <p v-if="settings && !settings.find((s) => s.key === 'feature.pipelines.enabled' && s.value === 'true')"
       class="mb-6 rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-[13px] text-amber-700">
      Экспериментальная функция «Пайплайны» выключена — Claude Code не сможет их выполнить, пока вы не включите
      флаг в <RouterLink :to="{ name: 'settings' }" class="underline">Настройках</RouterLink>.
    </p>

    <ErrorState v-if="error" :message="error" @retry="reload" />
    <SkeletonRows v-else-if="loading" :rows="3" />
    <EmptyState v-else-if="!pipelines?.length" icon="task" title="Пока нет ни одного пайплайна" />
    <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <RouterLink
        v-for="pipeline in pipelines"
        :key="pipeline.slug"
        :to="{ name: 'pipeline', params: { project, slug: pipeline.slug } }"
        class="rounded-2xl border border-border bg-panel p-5 transition hover:border-accent/40"
      >
        <h2 class="text-[14.5px] font-semibold tracking-tight text-content">{{ pipeline.name }}</h2>
        <p class="mt-1 font-mono text-[12px] text-faint">{{ pipeline.slug }}</p>
        <p v-if="pipeline.description" class="mt-2 text-[13px] text-muted">{{ pipeline.description }}</p>
        <p class="mt-3 text-[12px] text-faint">{{ pipeline.stepCount }} шагов · {{ pipeline.parameterCount }} параметров</p>
      </RouterLink>
    </div>
  </div>
</template>
```

- [ ] **Step 3: Write `PipelineBuilderView.vue`**

```vue
<script setup lang="ts">
import { computed, ref, toRef, watch } from 'vue'
import { useRouter } from 'vue-router'

import { createPipeline, fetchPipeline, updatePipeline, uploadPipelineAsset } from '@/api/client'
import type {
  PipelineParameterType,
  PipelineStepContentType,
  PipelineUpsertParameter,
  PipelineUpsertStep,
} from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'

const props = defineProps<{ project: string; slug?: string }>()
const project = toRef(props, 'project')
const editingSlug = toRef(props, 'slug')
const isEditing = computed(() => !!editingSlug.value)

const router = useRouter()

const slug = ref('')
const name = ref('')
const description = ref('')
const parameters = ref<PipelineUpsertParameter[]>([])
const steps = ref<PipelineUpsertStep[]>([])
const saving = ref(false)
const saveError = ref<string | null>(null)

async function loadForEdit() {
  if (!editingSlug.value) return
  const pipeline = await fetchPipeline(editingSlug.value)
  slug.value = pipeline.slug
  name.value = pipeline.name
  description.value = pipeline.description ?? ''
  parameters.value = pipeline.parameters.map((p) => ({
    name: p.name,
    label: p.label,
    type: p.type,
    required: p.required,
    defaultValue: p.defaultValue,
  }))
  steps.value = pipeline.steps.map((s) => ({
    title: s.title,
    contentType: s.contentType,
    promptText: s.promptText,
    assetId: s.assetId,
    referenceAssetId: s.referenceAssetId,
  }))
}

watch(editingSlug, loadForEdit, { immediate: true })

function addParameter() {
  parameters.value.push({ name: '', label: '', type: 'STRING' as PipelineParameterType, required: false, defaultValue: null })
}

function removeParameter(index: number) {
  parameters.value.splice(index, 1)
}

function addStep() {
  steps.value.push({ title: '', contentType: 'PROMPT' as PipelineStepContentType, promptText: '', assetId: null, referenceAssetId: null })
}

function removeStep(index: number) {
  steps.value.splice(index, 1)
}

function moveStep(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= steps.value.length) return
  const [step] = steps.value.splice(index, 1)
  steps.value.splice(target, 0, step)
}

async function onMdFileChosen(index: number, event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  const asset = await uploadPipelineAsset(file)
  steps.value[index].assetId = asset.id
}

async function onReferenceFileChosen(index: number, event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  const asset = await uploadPipelineAsset(file)
  steps.value[index].referenceAssetId = asset.id
}

async function save() {
  saving.value = true
  saveError.value = null
  try {
    const request = {
      slug: slug.value,
      name: name.value,
      description: description.value || null,
      projectScope: project.value,
      parameters: parameters.value,
      steps: steps.value,
    }
    const result = isEditing.value ? await updatePipeline(editingSlug.value!, request) : await createPipeline(request)
    await router.push({ name: 'pipeline', params: { project: project.value, slug: result.slug } })
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Pipelines"
      :title="isEditing ? 'Редактирование пайплайна' : 'Новый пайплайн'"
    />

    <ErrorState v-if="saveError" :message="saveError" />

    <div class="space-y-6">
      <section class="rounded-2xl border border-border bg-panel p-5">
        <label class="mb-1 block text-[12.5px] font-medium text-muted">Slug</label>
        <input
          v-model="slug"
          :disabled="isEditing"
          class="mb-4 w-full rounded-lg border border-border bg-elevated px-3 py-2 text-[13px] text-content disabled:opacity-60"
          placeholder="config-diff"
        />
        <label class="mb-1 block text-[12.5px] font-medium text-muted">Название</label>
        <input v-model="name" class="mb-4 w-full rounded-lg border border-border bg-elevated px-3 py-2 text-[13px] text-content" />
        <label class="mb-1 block text-[12.5px] font-medium text-muted">Описание</label>
        <textarea v-model="description" rows="2" class="w-full rounded-lg border border-border bg-elevated px-3 py-2 text-[13px] text-content" />
      </section>

      <section class="rounded-2xl border border-border bg-panel p-5">
        <div class="mb-3 flex items-center justify-between">
          <h2 class="text-[13px] font-semibold tracking-wide text-content uppercase">Параметры</h2>
          <button type="button" class="text-[12.5px] font-medium text-accent" @click="addParameter">+ Параметр</button>
        </div>
        <div v-for="(parameter, index) in parameters" :key="index" class="mb-3 flex items-center gap-2">
          <input v-model="parameter.name" placeholder="name" class="w-32 rounded-lg border border-border bg-elevated px-2 py-1.5 text-[12.5px] text-content" />
          <input v-model="parameter.label" placeholder="label" class="flex-1 rounded-lg border border-border bg-elevated px-2 py-1.5 text-[12.5px] text-content" />
          <select v-model="parameter.type" class="rounded-lg border border-border bg-elevated px-2 py-1.5 text-[12.5px] text-content">
            <option value="STRING">STRING</option>
            <option value="NUMBER">NUMBER</option>
            <option value="BOOLEAN">BOOLEAN</option>
          </select>
          <label class="flex items-center gap-1 text-[12px] text-muted">
            <input v-model="parameter.required" type="checkbox" /> required
          </label>
          <button type="button" class="text-faint hover:text-red-600" @click="removeParameter(index)">
            <AppIcon name="trash" class="size-4" />
          </button>
        </div>
      </section>

      <section class="rounded-2xl border border-border bg-panel p-5">
        <div class="mb-3 flex items-center justify-between">
          <h2 class="text-[13px] font-semibold tracking-wide text-content uppercase">Шаги</h2>
          <button type="button" class="text-[12.5px] font-medium text-accent" @click="addStep">+ Шаг</button>
        </div>
        <div v-for="(step, index) in steps" :key="index" class="mb-4 rounded-xl border border-border bg-elevated p-4">
          <div class="mb-2 flex items-center gap-2">
            <span class="text-[12px] text-faint">#{{ index + 1 }}</span>
            <input v-model="step.title" placeholder="Название шага" class="flex-1 rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content" />
            <button type="button" class="text-faint hover:text-content" :disabled="index === 0" @click="moveStep(index, -1)">↑</button>
            <button type="button" class="text-faint hover:text-content" :disabled="index === steps.length - 1" @click="moveStep(index, 1)">↓</button>
            <button type="button" class="text-faint hover:text-red-600" @click="removeStep(index)">
              <AppIcon name="trash" class="size-4" />
            </button>
          </div>
          <div class="mb-2 flex gap-3 text-[12.5px] text-muted">
            <label class="flex items-center gap-1"><input v-model="step.contentType" type="radio" value="PROMPT" /> Prompt-текст</label>
            <label class="flex items-center gap-1"><input v-model="step.contentType" type="radio" value="MD_FILE" /> .md файл</label>
          </div>
          <textarea
            v-if="step.contentType === 'PROMPT'"
            v-model="step.promptText"
            rows="3"
            placeholder="Инструкция для Claude — можно использовать {{paramName}}"
            class="w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content"
          />
          <div v-else class="text-[12.5px] text-muted">
            <input type="file" accept=".md" @change="onMdFileChosen(index, $event)" />
            <span v-if="step.assetId" class="ml-2">Загружен: asset #{{ step.assetId }}</span>
          </div>
          <div class="mt-2 text-[12.5px] text-muted">
            <label class="block">Ссылочный файл (необязательно, например html-шаблон отчёта):</label>
            <input type="file" @change="onReferenceFileChosen(index, $event)" />
            <span v-if="step.referenceAssetId" class="ml-2">Загружен: asset #{{ step.referenceAssetId }}</span>
          </div>
        </div>
      </section>

      <button
        type="button"
        :disabled="saving"
        class="rounded-lg bg-accent px-4 py-2 text-[13px] font-medium text-accent-fg transition hover:bg-accent-hover disabled:opacity-50"
        @click="save"
      >
        {{ saving ? 'Сохранение…' : 'Сохранить' }}
      </button>
    </div>
  </div>
</template>
```

- [ ] **Step 4: Type-check**

Run: `cd ui && npm run type-check`
Expected: no errors

- [ ] **Step 5: Manually verify**

Run `./gradlew bootRun`, open `http://localhost:8080/settings` and enable Pipelines, then `http://localhost:8080/p/<any-project>/pipelines/new`, fill in a slug/name, add one PROMPT step, save, confirm it redirects to the (not-yet-built, Task 9) detail route without a console error other than a 404 view — this is expected until Task 9 lands. Stop `bootRun`.

- [ ] **Step 6: Commit**

```bash
git add ui/src/views/PipelinesView.vue ui/src/views/PipelineBuilderView.vue ui/src/router/index.ts
git commit -m "feat(ui): add pipelines list and manual step builder"
```

---

## Task 9: Frontend — Pipeline detail (+ history) and run view

**Files:**
- Create: `ui/src/views/PipelineView.vue`
- Create: `ui/src/views/PipelineRunView.vue`
- Modify: `ui/src/router/index.ts`
- Modify: `ui/src/components/AppSidebar.vue` (optional nav entry point is via `PipelinesView`, already reachable from the project page — this task adds a link from `ProjectView.vue`)
- Modify: `ui/src/views/ProjectView.vue`

**Interfaces:**
- Consumes: `fetchPipeline`, `deletePipeline`, `fetchPipelineRuns`, `fetchPipelineRun` (Task 6); `ConfirmDialog`, `PageHeader`, `ErrorState`, `SkeletonRows`, `EmptyState`, `AppIcon` (existing).
- Produces: routes `pipeline` (`/p/:project/pipelines/:slug`), `pipeline-run` (`/p/:project/pipelines/:slug/runs/:runId`) — this is the link the `pipelines` skill (Task 10) prints in chat.

- [ ] **Step 1: Add the routes**

In `ui/src/router/index.ts`, add after the `pipeline-edit` route added in Task 8:

```ts
    {
      path: '/p/:project/pipelines/:slug',
      name: 'pipeline',
      component: () => import('@/views/PipelineView.vue'),
      props: true,
    },
    {
      path: '/p/:project/pipelines/:slug/runs/:runId',
      name: 'pipeline-run',
      component: () => import('@/views/PipelineRunView.vue'),
      props: true,
    },
```

Route ordering note: Vue Router matches by specificity, not declaration order, for these static/dynamic segment combinations, but keep `pipeline-edit`/`pipeline-new` declared before `pipeline` for readability since they share the `/p/:project/pipelines/...` prefix.

- [ ] **Step 2: Write `PipelineView.vue`**

```vue
<script setup lang="ts">
import { ref, toRef } from 'vue'
import { useRouter } from 'vue-router'

import { deletePipeline, fetchPipeline, fetchPipelineRuns } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { projectLocation } from '@/lib/links'

const props = defineProps<{ project: string; slug: string }>()
const project = toRef(props, 'project')
const slug = toRef(props, 'slug')

const { data: pipeline, error, loading } = useAsyncData(() => fetchPipeline(slug.value), [slug])
const { data: runs, loading: runsLoading } = useAsyncData(() => fetchPipelineRuns(slug.value), [slug])

const router = useRouter()
const showDeleteConfirm = ref(false)
const deleting = ref(false)
const deleteError = ref<string | null>(null)

async function confirmDelete() {
  deleting.value = true
  deleteError.value = null
  try {
    await deletePipeline(slug.value)
    await router.push({ name: 'pipelines', params: { project: project.value } })
  } catch (cause) {
    deleteError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    deleting.value = false
    showDeleteConfirm.value = false
  }
}

const STATUS_LABEL: Record<string, string> = {
  RUNNING: 'Выполняется',
  DONE: 'Готово',
  FAILED: 'Ошибка',
  ABORTED: 'Прервано',
}
</script>

<template>
  <div>
    <ErrorState v-if="error" :message="error" />
    <template v-else>
      <PageHeader eyebrow="Pipeline" :title="pipeline?.name ?? slug" :subtitle="pipeline?.description ?? undefined">
        <template #actions>
          <RouterLink
            :to="{ name: 'pipeline-edit', params: { project, slug } }"
            class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
          >
            Редактировать
          </RouterLink>
          <button
            type="button"
            class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-red-500/50 hover:text-red-600"
            @click="showDeleteConfirm = true"
          >
            <AppIcon name="trash" class="size-4" />
            Удалить
          </button>
          <RouterLink
            :to="projectLocation(project)"
            class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
          >
            <AppIcon name="arrowLeft" class="size-4" />
            Назад
          </RouterLink>
        </template>
      </PageHeader>

      <SkeletonRows v-if="loading" :rows="2" class="mb-6" />

      <section v-else-if="pipeline" class="mb-9">
        <h2 class="mb-3 text-[13px] font-semibold tracking-wide text-content uppercase">Шаги</h2>
        <ol class="space-y-2">
          <li v-for="step in pipeline.steps" :key="step.id" class="rounded-xl border border-border bg-panel p-4">
            <p class="text-[13px] font-medium text-content">{{ step.orderIndex + 1 }}. {{ step.title }}</p>
            <p class="mt-1 text-[12px] text-faint">{{ step.contentType === 'PROMPT' ? 'Prompt' : '.md файл' }}</p>
          </li>
        </ol>
      </section>

      <section>
        <h2 class="mb-3 text-[13px] font-semibold tracking-wide text-content uppercase">История запусков</h2>
        <SkeletonRows v-if="runsLoading" :rows="2" />
        <EmptyState v-else-if="!runs?.length" icon="task" title="Пока не было ни одного запуска" />
        <ul v-else class="space-y-2">
          <li v-for="run in runs" :key="run.id">
            <RouterLink
              :to="{ name: 'pipeline-run', params: { project, slug, runId: run.id } }"
              class="flex items-center justify-between rounded-xl border border-border bg-panel px-4 py-3 transition hover:border-accent/40"
            >
              <span class="text-[13px] text-content">Запуск #{{ run.id }}</span>
              <span class="text-[12px] text-faint">{{ STATUS_LABEL[run.status] }} · {{ new Date(run.startedAt).toLocaleString() }}</span>
            </RouterLink>
          </li>
        </ul>
      </section>
    </template>

    <ConfirmDialog
      :open="showDeleteConfirm"
      title="Удалить этот пайплайн?"
      message="Определение и история запусков будут удалены безвозвратно."
      :loading="deleting"
      @confirm="confirmDelete"
      @cancel="showDeleteConfirm = false"
    />
    <p v-if="deleteError" class="mt-3 text-[12.5px] text-red-600">{{ deleteError }}</p>
  </div>
</template>
```

- [ ] **Step 3: Write `PipelineRunView.vue`**

```vue
<script setup lang="ts">
import { computed, toRef } from 'vue'

import { fetchPipelineRun } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'

const props = defineProps<{ project: string; slug: string; runId: string }>()
const runId = toRef(props, 'runId')

const { data: run, error, loading } = useAsyncData(() => fetchPipelineRun(Number(runId.value)), [runId])

const STEP_ICON: Record<string, string> = {
  PENDING: 'chevron',
  RUNNING: 'refresh',
  DONE: 'check',
  FAILED: 'warning',
  SKIPPED: 'arrowLeft',
}

const STEP_COLOR: Record<string, string> = {
  PENDING: 'text-faint',
  RUNNING: 'text-accent',
  DONE: 'text-green-600',
  FAILED: 'text-red-600',
  SKIPPED: 'text-faint',
}

const title = computed(() => (run.value ? `Запуск #${run.value.id} — ${run.value.pipelineSlug}` : `Запуск #${runId.value}`))
</script>

<template>
  <div>
    <PageHeader eyebrow="Pipeline run" :title="title" />

    <ErrorState v-if="error" :message="error" />
    <SkeletonRows v-else-if="loading" :rows="3" />

    <ol v-else-if="run" class="space-y-3">
      <li v-for="step in run.steps" :key="step.id" class="rounded-2xl border border-border bg-panel p-4">
        <div class="flex items-center gap-2.5">
          <AppIcon :name="STEP_ICON[step.status]" class="size-4" :class="STEP_COLOR[step.status]" />
          <span class="text-[13.5px] font-medium text-content">{{ step.orderIndex + 1 }}. {{ step.title }}</span>
        </div>
        <p v-if="step.note" class="mt-2 ml-6.5 text-[12.5px] text-muted">{{ step.note }}</p>
      </li>
    </ol>
  </div>
</template>
```

- [ ] **Step 4: Link to Pipelines from the project page**

In `ui/src/views/ProjectView.vue`, the `#actions` slot of `<PageHeader>` currently has a "Delete" button then a "Graph" link. Add a "Pipelines" link between them:

```vue
        <button
          type="button"
          class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-red-500/50 hover:text-red-600"
          @click="showDeleteConfirm = true"
        >
          <AppIcon name="trash" class="size-4" />
          Delete
        </button>
        <RouterLink
          :to="{ name: 'pipelines', params: { project } }"
          class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
        >
          <AppIcon name="task" class="size-4" />
          Pipelines
        </RouterLink>
        <RouterLink
          :to="graphLocation(project)"
          class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
        >
          <AppIcon name="graph" class="size-4" />
          Graph
        </RouterLink>
```

(Only the new `RouterLink` block is an addition — the "Delete" button and the "Graph" link above/below it are unchanged, shown here for placement context.)

- [ ] **Step 5: Type-check**

Run: `cd ui && npm run type-check`
Expected: no errors

- [ ] **Step 6: Manually verify the whole flow**

Run `./gradlew bootRun`. In the browser: `/settings` → enable Пайплайны → `/p/<project>/pipelines/new` → create a pipeline with 2 PROMPT steps → confirm redirect lands on `/p/<project>/pipelines/<slug>` and shows both steps and an empty run history. Then, from a terminal, exercise the run lifecycle directly against the MCP-tool-backing services isn't possible without an MCP client here — instead confirm the read path by hitting `curl -s http://localhost:8080/api/pipelines/<slug>` and `curl -s -X POST http://localhost:8080/api/pipeline-assets -F file=@README.md` manually return sane JSON. Stop `bootRun`.

- [ ] **Step 7: Commit**

```bash
git add ui/src/views/PipelineView.vue ui/src/views/PipelineRunView.vue ui/src/router/index.ts ui/src/views/ProjectView.vue
git commit -m "feat(ui): add pipeline detail, run history, and run detail views"
```

---

## Task 10: The `pipelines` skill (execution engine)

**Files:**
- Create: `.claude/skills/pipelines/SKILL.md`

**Interfaces:**
- Consumes: MCP tools `pipeline_list`, `pipeline_get`, `pipeline_run_start`, `pipeline_run_step_update`, `pipeline_run_complete`, `pipeline_run_get` (Task 5).

- [ ] **Step 1: Write the skill**

```markdown
---
name: pipelines
description: Use when the user asks to run/execute a named pipeline built in the memory-mcp dashboard - phrases like "выполни пайплайн X", "запусти пайплайн X", "run the X pipeline". Do NOT use this for anything else; pipelines are only ever authored by hand in the dashboard UI, never by this skill.
---

# Running a memory-mcp pipeline

A pipeline is a named, linear sequence of steps a human hand-built in the memory-mcp dashboard
(behind the "Pipelines" experimental flag). memory-mcp only stores the definition and tracks run
state - **you** are the execution engine. Each step's "work" is bounded by whatever tools you
already have (Bash, Read, Grep, WebFetch, other MCP tools) - there is no separate sandbox or
scripting language. Doing the step's actual work is no different from doing that work unprompted;
the only new part is checking state back into memory-mcp as you go.

## Steps

1. **Resolve the pipeline.** If the user gave an exact slug, call `pipeline_get(slug)` directly.
   Otherwise call `pipeline_list(projectScope)` first and match by name.
   - If `pipeline_get`/`pipeline_list` errors because the feature flag is off, tell the user
     plainly (don't retry) - point them at Settings in the dashboard.
2. **Collect parameters.** `pipeline_get` returns `parameters` (name/label/type/required/default).
   If the user's message already supplied values for every required parameter, use those.
   Otherwise ask for the missing ones before starting - don't guess.
3. **Start the run:** `pipeline_run_start(slug, parametersJson)` with parameters as a JSON object
   string, e.g. `{"folder": "src/config"}`. This returns `runId` and the ordered step list
   (`orderIndex`, `title`, `instructionText`, `referenceText`).
4. **Print a checklist** in chat before starting, one line per step, all unchecked:
   ```
   - [ ] 1. Check config history
   - [ ] 2. Save report
   ```
5. **Work through steps in order.** For each step:
   - Substitute `{{paramName}}` in `instructionText` with the parameter values you collected.
   - If `referenceText` is present, treat it as supplementary reference material (e.g. an example
     report format) for that step, not an instruction to follow literally.
   - Do the actual work using your normal tools.
   - Update the checklist line in chat: `- [x]` on success, `- [!]` on failure.
   - Call `pipeline_run_step_update(runId, orderIndex, status, note)` with `status` = `DONE` or
     `FAILED` (`SKIPPED` if the user told you to skip this step), and a short `note` summarizing
     what happened.
6. **On FAILED: stop.** Do not silently continue to the next step. Tell the user what failed and
   why, and ask how to proceed - retry the step, skip it, or abort the whole run
   (`pipeline_run_complete(runId, "ABORTED")`).
7. **On completing every step:** call `pipeline_run_complete(runId, "DONE")`, then print a final
   summary with a link to the dashboard's read-only run view:
   `{dashboardBaseUrl}/p/{projectScope}/pipelines/{slug}/runs/{runId}` (derive `dashboardBaseUrl`
   from the MCP server URL the user connected to, typically `http://localhost:8080`).

## Resuming an interrupted run

If the user asks to continue a pipeline run from an earlier session, call
`pipeline_run_get(runId)` to see which steps are already `DONE`/`FAILED`/`SKIPPED`, and resume
from the first `PENDING` step - don't redo finished steps.
```

- [ ] **Step 2: Commit**

```bash
git add .claude/skills/pipelines/SKILL.md
git commit -m "docs: add pipelines skill to drive pipeline execution from chat"
```
