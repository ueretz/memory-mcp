package ru.iuribabalin.memorymcp.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.entity.Folder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class FolderRepositoryTest {

    @Autowired
    private FolderRepository repository;

    @Test
    void listsDirectChildrenOnly() {
        Folder root = save("folder-repo-test-root", null);
        Folder child = save("folder-repo-test-child", root);
        save("folder-repo-test-grandchild", child);

        List<Folder> topLevel = repository.listChildren(null, "folder-repo-test-project", null);
        assertThat(topLevel).extracting(Folder::getName).contains("folder-repo-test-root");

        List<Folder> children = repository.listChildren("folder-repo-test-root", "folder-repo-test-project", null);
        assertThat(children).extracting(Folder::getName).containsExactly("folder-repo-test-child");
    }

    private Folder save(String name, Folder parent) {
        Folder folder = new Folder();
        folder.setName(name);
        folder.setDescription("desc");
        folder.setProjectScope("folder-repo-test-project");
        folder.setParent(parent);
        folder.setCreatedAt(Instant.now());
        folder.setUpdatedAt(Instant.now());
        return repository.saveAndFlush(folder);
    }
}
