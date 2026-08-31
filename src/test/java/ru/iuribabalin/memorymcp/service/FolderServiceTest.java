package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.FolderSummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class FolderServiceTest {

    @Autowired
    private FolderService folderService;

    @Test
    void createsNestedFolderAndListsItAsAChild() {
        folderService.create("folder-svc-test-project", null, "folder-svc-test-root", "root desc", null, "Tester");
        FolderSummary child = folderService.create(
                "folder-svc-test-project", null, "folder-svc-test-child", "child desc", "folder-svc-test-root", "Tester");

        assertThat(child.parentFolder()).isEqualTo("folder-svc-test-root");
        assertThat(folderService.listChildren("folder-svc-test-project", null, "folder-svc-test-root"))
                .extracting(FolderSummary::name)
                .containsExactly("folder-svc-test-child");
    }

    @Test
    void rejectsParentFromADifferentProject() {
        folderService.create("folder-svc-test-project-a", null, "folder-svc-test-a-root", "desc", null, "Tester");

        assertThatThrownBy(() -> folderService.create(
                "folder-svc-test-project-b", null, "folder-svc-test-b-child", "desc", "folder-svc-test-a-root", "Tester"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReScopingAnExistingFolderToADifferentProject() {
        folderService.create("folder-svc-test-rescope-a", null, "folder-svc-test-rescope-target", "desc", null, "Tester");

        assertThatThrownBy(() -> folderService.create(
                "folder-svc-test-rescope-b", null, "folder-svc-test-rescope-target", "desc", null, "Tester"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsUpdatingDescriptionOfAnExistingFolderInTheSameScope() {
        folderService.create(
                "folder-svc-test-update-project", null, "folder-svc-test-update-target", "original desc", null, "Tester");

        FolderSummary updated = folderService.create(
                "folder-svc-test-update-project", null, "folder-svc-test-update-target", "updated desc", null, "Tester");

        assertThat(updated.description()).isEqualTo("updated desc");
        assertThat(updated.projectScope()).isEqualTo("folder-svc-test-update-project");
    }

    @Test
    void deleteRemovesTheFolder() {
        folderService.create("folder-svc-test-delete-project", null, "folder-svc-test-delete-target", "desc", null, "Tester");

        boolean deleted = folderService.delete("folder-svc-test-delete-target");

        assertThat(deleted).isTrue();
        assertThat(folderService.listChildren("folder-svc-test-delete-project", null, null))
                .extracting(FolderSummary::name)
                .doesNotContain("folder-svc-test-delete-target");
    }

    @Test
    void deleteReturnsFalseWhenFolderDoesNotExist() {
        boolean deleted = folderService.delete("folder-svc-test-delete-nonexistent");

        assertThat(deleted).isFalse();
    }
}
