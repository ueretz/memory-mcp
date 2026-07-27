package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LinkParser {

    // Allows dots so [[fully.qualified.ClassName]] (used by LOCATION entries) links correctly,
    // alongside plain kebab-case entry names.
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[\\[([A-Za-z0-9_.-]+)]]");

    public Set<String> extractLinkedNames(String content) {
        Set<String> names = new LinkedHashSet<>();
        if (content == null) {
            return names;
        }
        Matcher matcher = LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
