package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import ru.iuribabalin.memorymcp.dto.SaveMemoryRequest;
import ru.iuribabalin.memorymcp.entity.MemoryNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class RepositoryScanner {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "build", "target", "dist", "node_modules", ".gradle", ".idea",
            "out", ".next", "venv", "__pycache__", ".claude"
    );

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE_PATTERN =
            Pattern.compile("(?m)^\\s*(?:public\\s+|final\\s+|abstract\\s+)*(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;");

    private final MemoryService memoryService;

    public RepositoryScanner(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public int scan(String projectScope, Path rootDir) throws IOException {
        if (!Files.isDirectory(rootDir)) {
            throw new IllegalArgumentException("Not a directory: " + rootDir);
        }

        List<Path> files;
        try (Stream<Path> walk = Files.walk(rootDir)) {
            files = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> isNotExcluded(rootDir, path))
                    .toList();
        }

        Map<Path, JavaInfo> javaInfoByPath = new HashMap<>();
        Map<String, Path> fqcnToPath = new HashMap<>();
        for (Path file : files) {
            if (file.toString().endsWith(".java")) {
                JavaInfo info = parseJava(file);
                if (info != null) {
                    javaInfoByPath.put(file, info);
                    fqcnToPath.put(info.fqcn(), file);
                }
            }
        }

        int count = 0;
        for (Path file : files) {
            String relativePath = rootDir.relativize(file).toString();
            JavaInfo javaInfo = javaInfoByPath.get(file);

            String name;
            String description;
            String content;
            if (javaInfo != null) {
                name = javaInfo.fqcn();
                description = javaInfo.typeName() + " - class in " + javaInfo.packageName();
                content = javaContent(javaInfo, relativePath, fqcnToPath);
            } else {
                name = projectScope + ":" + relativePath;
                description = relativePath;
                content = "File at `" + relativePath + "`.";
            }

            memoryService.save(new SaveMemoryRequest(
                    name, MemoryNode.Type.LOCATION, description, content, projectScope, null, relativePath));
            count++;
        }
        return count;
    }

    private String javaContent(JavaInfo javaInfo, String relativePath, Map<String, Path> fqcnToPath) {
        StringBuilder sb = new StringBuilder("Java class `").append(javaInfo.fqcn())
                .append("` at `").append(relativePath).append("`.\n");
        List<String> inProjectImports = javaInfo.imports().stream()
                .filter(fqcnToPath::containsKey)
                .distinct()
                .toList();
        if (!inProjectImports.isEmpty()) {
            sb.append("\nDepends on:\n");
            for (String imp : inProjectImports) {
                sb.append("- [[").append(imp).append("]]\n");
            }
        }
        return sb.toString();
    }

    private boolean isNotExcluded(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if (EXCLUDED_DIRS.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }

    private JavaInfo parseJava(Path file) throws IOException {
        String content = Files.readString(file);
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(content);
        String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";

        Matcher typeMatcher = TYPE_PATTERN.matcher(content);
        if (!typeMatcher.find()) {
            return null;
        }
        String typeName = typeMatcher.group(1);
        String fqcn = packageName.isEmpty() ? typeName : packageName + "." + typeName;

        List<String> imports = new ArrayList<>();
        Matcher importMatcher = IMPORT_PATTERN.matcher(content);
        while (importMatcher.find()) {
            imports.add(importMatcher.group(1));
        }
        return new JavaInfo(packageName, typeName, fqcn, imports);
    }

    private record JavaInfo(String packageName, String typeName, String fqcn, List<String> imports) {
    }
}
