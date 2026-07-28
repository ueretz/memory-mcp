package ru.iuribabalin.memorymcp.ui;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.SetupInfo;

@RestController
public class SetupController {

    private static final String SKILL_INSTALL_PATH = "~/.claude/skills/memory-mcp/SKILL.md";

    @GetMapping("/api/setup")
    public SetupInfo info(HttpServletRequest request) {
        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        String mcpServerUrl = baseUrl + "/mcp";
        String command = """
                claude mcp add --scope user --transport http memory-mcp %s"""
                .formatted(mcpServerUrl);
        return new SetupInfo(command, mcpServerUrl, SKILL_INSTALL_PATH);
    }

    @GetMapping("/api/setup/skill")
    public ResponseEntity<Resource> skill() {
        Resource resource = new ClassPathResource("skill/SKILL.md");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"SKILL.md\"")
                .body(resource);
    }
}
