package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.MemoryEntryDetail;
import ru.iuribabalin.memorymcp.dto.SaveMemoryRequest;
import ru.iuribabalin.memorymcp.entity.MemoryNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class MemoryServiceTest {

    @Autowired
    private MemoryService memoryService;
    @Autowired
    private FolderService folderService;

    @Test
    void savingIntoAFolderExcludesItFromTheRootListing() {
        folderService.create("mem-svc-test-project", null, "mem-svc-test-folder", "desc", null, "Tester");
        memoryService.save(new SaveMemoryRequest(
                "mem-svc-test-in-folder", MemoryNode.Type.PROJECT, "d", "c",
                "mem-svc-test-project", null, "mem-svc-test-folder", null, "Tester"));
        memoryService.save(new SaveMemoryRequest(
                "mem-svc-test-at-root", MemoryNode.Type.PROJECT, "d", "c",
                "mem-svc-test-project", null, null, null, "Tester"));

        assertThat(memoryService.list(null, "mem-svc-test-project", null, null, 50, 0))
                .extracting(s -> s.name())
                .containsExactly("mem-svc-test-at-root");

        assertThat(memoryService.list(null, "mem-svc-test-project", null, "mem-svc-test-folder", 50, 0))
                .extracting(s -> s.name())
                .containsExactly("mem-svc-test-in-folder");

        MemoryEntryDetail detail = memoryService.get("mem-svc-test-in-folder");
        assertThat(detail.folder()).isEqualTo("mem-svc-test-folder");
    }

    @Test
    void rejectsAFolderFromADifferentProject() {
        folderService.create("mem-svc-test-project-a", null, "mem-svc-test-a-folder", "desc", null, "Tester");

        assertThatThrownBy(() -> memoryService.save(new SaveMemoryRequest(
                "mem-svc-test-cross-project", MemoryNode.Type.PROJECT, "d", "c",
                "mem-svc-test-project-b", null, "mem-svc-test-a-folder", null, "Tester")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
