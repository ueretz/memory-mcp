package ru.iuribabalin.memorymcp.ui;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.iuribabalin.memorymcp.dto.SetupInfo;
import ru.iuribabalin.memorymcp.dto.SkillInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Each entry in {@link #SKILLS} is one downloadable Claude Code skill. {@code memory-mcp} is a
 * single file, downloaded as-is; the other two have an {@code assets/} subfolder, so they're
 * zipped with the skill's own directory name as the top-level entry - unzip and drop straight
 * into {@code ~/.claude/skills/}.
 */
@RestController
public class SetupController {

    private record SkillDefinition(String id, String title, String description, List<String> classpathFiles) {
    }

    private static final List<SkillDefinition> SKILLS = List.of(
            new SkillDefinition("memory-mcp",
                    "memory-mcp",
                    "Teaches Claude to use this server's memory instead of writing notes/plans/reports to local files.",
                    List.of("skill/SKILL.md")),
            new SkillDefinition("agent-task-board",
                    "agent-task-board",
                    "Auto-decomposes a substantive task into a tracked subtask board, with a plan-confirmation checkpoint before implementation starts.",
                    List.of("skill/agent-task-board/SKILL.md")),
            new SkillDefinition("agent-task-report",
                    "agent-task-report",
                    "Builds and saves dashboard-styled HTML reports (used by agent-task-board, or standalone).",
                    List.of("skill/agent-task-report/SKILL.md",
                            "skill/agent-task-report/assets/agent_task_report_template.html"))
    );

    @GetMapping("/api/setup")
    public SetupInfo info(HttpServletRequest request) {
        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        String mcpServerUrl = baseUrl + "/mcp";
        String command = """
                claude mcp add --scope user --transport http memory-mcp %s"""
                .formatted(mcpServerUrl);
        List<SkillInfo> skills = SKILLS.stream()
                .map(skill -> new SkillInfo(
                        skill.id(),
                        skill.title(),
                        skill.description(),
                        skill.classpathFiles().size() == 1
                                ? "~/.claude/skills/%s/SKILL.md".formatted(skill.id())
                                : "~/.claude/skills/%s/ (unzip here)".formatted(skill.id()),
                        "/api/setup/skills/" + skill.id()))
                .toList();
        return new SetupInfo(command, mcpServerUrl, skills);
    }

    @GetMapping("/api/setup/skills/{id}")
    public ResponseEntity<Resource> skill(@PathVariable String id) {
        SkillDefinition definition = SKILLS.stream()
                .filter(skill -> skill.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No skill '" + id + "'"));

        if (definition.classpathFiles().size() == 1) {
            Resource resource = new ClassPathResource(definition.classpathFiles().getFirst());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/markdown"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"SKILL.md\"")
                    .body(resource);
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + definition.id() + ".zip\"")
                .body(new ByteArrayResource(zip(definition)));
    }

    private byte[] zip(SkillDefinition definition) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (String classpathFile : definition.classpathFiles()) {
                // classpathFile looks like "skill/agent-task-report/assets/foo.html" - strip the
                // leading "skill/" so the zip entry starts with the skill's own directory name.
                String entryName = classpathFile.substring("skill/".length());
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(new ClassPathResource(classpathFile).getContentAsByteArray());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }
}
