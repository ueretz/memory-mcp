package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The skills this server hands out from {@code src/main/resources/skill} are also checked into
 * {@code .claude/skills} so this repo's own Claude sessions use the same rules. Nothing keeps the
 * two trees in step, so an edit to one copy silently ships a different skill than the one being
 * dogfooded here - this test is that guard.
 */
class SkillAssetsInSyncTest {

    private static final Path SHIPPED_ROOT = Path.of("src/main/resources/skill");
    private static final Path LOCAL_ROOT = Path.of(".claude/skills");

    /** Shipped classpath file (relative to {@link #SHIPPED_ROOT}) -> its copy under {@link #LOCAL_ROOT}. */
    private static final Map<String, String> COPIES = Map.of(
            "SKILL.md", "memory-mcp/SKILL.md",
            "agent-task-board/SKILL.md", "agent-task-board/SKILL.md",
            "agent-task-report/SKILL.md", "agent-task-report/SKILL.md",
            "agent-task-report/assets/agent_task_report_template.html",
            "agent-task-report/assets/agent_task_report_template.html");

    @Test
    void everyShippedSkillFileIsByteIdenticalToTheCheckedInCopy() throws IOException {
        for (Map.Entry<String, String> copy : COPIES.entrySet()) {
            Path shipped = SHIPPED_ROOT.resolve(copy.getKey());
            Path local = LOCAL_ROOT.resolve(copy.getValue());

            assertThat(shipped).as("shipped skill file %s", shipped).isRegularFile();
            assertThat(local).as("checked-in copy %s", local).isRegularFile();
            assertThat(Files.readAllBytes(local))
                    .as("%s and %s drifted apart - copy one over the other", shipped, local)
                    .isEqualTo(Files.readAllBytes(shipped));
        }
    }

    @Test
    void everyFileUnderTheShippedSkillTreeIsCovered() throws IOException {
        try (Stream<Path> files = Files.walk(SHIPPED_ROOT)) {
            List<String> shipped = files.filter(Files::isRegularFile)
                    .map(path -> SHIPPED_ROOT.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();

            assertThat(shipped)
                    .as("a shipped skill file has no checked-in counterpart declared in COPIES - "
                            + "add it to .claude/skills and to this test's map")
                    .containsExactlyInAnyOrderElementsOf(COPIES.keySet());
        }
    }
}
