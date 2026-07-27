package ru.iuribabalin.memorymcp.ui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.MemoryMcpApplication;
import ru.iuribabalin.memorymcp.dto.SetupInfo;

@RestController
public class SetupController {

    private static final String SKILL_INSTALL_PATH = "~/.claude/skills/memory-mcp/SKILL.md";

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String datasourceUsername;

    @Value("${spring.datasource.password}")
    private String datasourcePassword;

    @GetMapping("/api/setup")
    public SetupInfo info() {
        String javaExecutable = System.getProperty("java.home") + "/bin/java";
        String jarPath = new ApplicationHome(MemoryMcpApplication.class).getSource().getAbsolutePath();
        String command = """
                claude mcp add --scope user memory-mcp \\
                  -e SPRING_DATASOURCE_URL=%s \\
                  -e SPRING_DATASOURCE_USERNAME=%s \\
                  -e SPRING_DATASOURCE_PASSWORD=%s \\
                  -- %s -jar %s --spring.profiles.active=mcp-stdio"""
                .formatted(datasourceUrl, datasourceUsername, datasourcePassword, javaExecutable, jarPath);
        return new SetupInfo(command, jarPath, javaExecutable, SKILL_INSTALL_PATH);
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
