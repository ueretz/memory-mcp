package ru.iuribabalin.memorymcp.service;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;
import ru.iuribabalin.memorymcp.dto.MemoryEntryDetail;
import ru.iuribabalin.memorymcp.entity.MemoryNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a memory entry into a downloadable PDF or markdown file. REPORT entries already hold a
 * full HTML document (rendered as-is); every other type holds markdown, wrapped in a minimal
 * print stylesheet before going to {@link PdfRenderer}.
 */
@Service
public class MemoryExportService {

    private static final Pattern DATA_THEME_ATTR = Pattern.compile("data-theme=\"[^\"]*\"");
    private static final Pattern HTML_TAG = Pattern.compile("(?i)<html");
    private static final Pattern HEAD_CLOSE = Pattern.compile("(?i)</head>");
    private static final Pattern BODY_CLOSE = Pattern.compile("(?i)</body>");

    private static final String PRINT_TAB_OVERRIDE = """
            <style>
              /* A PDF has no click-to-switch-tabs interaction, so every tab panel a report
                 hides behind "active" state needs to print, not just whichever was active when
                 it was saved - covers this project's own task-planner-style .tab-panel markup
                 plus the generic ARIA tabpanel role. Reports that hide content some other way
                 are unaffected - there's no signal to hook into without knowing their markup. */
              .tab-panel, .tab-content, .tabpanel, [role="tabpanel"] { display: block !important; }
              .tabs, .tab-nav, [role="tablist"] { display: none !important; }
            </style>
            """;

    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder().build();
    private final PdfRenderer pdfRenderer;

    public MemoryExportService(PdfRenderer pdfRenderer) {
        this.pdfRenderer = pdfRenderer;
    }

    public byte[] toPdf(MemoryEntryDetail entry) {
        String html = entry.type() == MemoryNode.Type.REPORT
                ? expandTabsForPrint(forceLightTheme(entry.content()))
                : wrapMarkdown(entry);
        return pdfRenderer.renderToPdf(html);
    }

    /**
     * Multi-section REPORT entries (e.g. the task-planner skill's tabbed plans) hide every tab
     * but the active one behind CSS - fine for browsing, but a PDF has no tab strip to click, so
     * a reader who only sees the tab that happened to be active when the report was saved is
     * missing the rest of the document. Force every panel visible and drop the now-inert tab
     * nav so the export reads as one continuous document instead of stacking dead controls.
     */
    private String expandTabsForPrint(String html) {
        if (HEAD_CLOSE.matcher(html).find()) {
            return HEAD_CLOSE.matcher(html).replaceFirst(Matcher.quoteReplacement(PRINT_TAB_OVERRIDE) + "</head>");
        }
        if (BODY_CLOSE.matcher(html).find()) {
            return BODY_CLOSE.matcher(html).replaceFirst(Matcher.quoteReplacement(PRINT_TAB_OVERRIDE) + "</body>");
        }
        return html + PRINT_TAB_OVERRIDE;
    }

    /**
     * REPORT entries follow the Artifacts convention of a `data-theme="dark"/"light"` attribute
     * on the root <html> element overriding prefers-color-scheme (see the dashboard's own
     * ReportView.vue, which uses the same attribute to let a viewer flip a report's theme). A
     * PDF is a fixed export, so it's always forced light here independent of whatever theme the
     * report currently defaults to or was last viewed in.
     */
    private String forceLightTheme(String html) {
        if (DATA_THEME_ATTR.matcher(html).find()) {
            return DATA_THEME_ATTR.matcher(html).replaceFirst("data-theme=\"light\"");
        }
        return HTML_TAG.matcher(html).replaceFirst("<html data-theme=\"light\"");
    }

    public String toMarkdown(MemoryEntryDetail entry) {
        if (entry.type() == MemoryNode.Type.REPORT) {
            throw new UnsupportedExportException(
                    "'" + entry.name() + "' is a REPORT entry (HTML content) - it has no markdown form, use the PDF export instead.");
        }
        return entry.content();
    }

    private String wrapMarkdown(MemoryEntryDetail entry) {
        // [[wiki-links]] resolve to real links only inside the dashboard's own graph; in a
        // standalone PDF there's nothing to link to, so just keep the name emphasised.
        String withoutWikiLinks = entry.content().replaceAll("\\[\\[([^\\]\\n]+)]]", "**$1**");
        String bodyHtml = htmlRenderer.render(markdownParser.parse(withoutWikiLinks));
        return """
                <!doctype html>
                <html>
                <head>
                <meta charset="utf-8">
                <title>%s</title>
                <style>
                  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; color: #17171f; line-height: 1.55; padding: 0 4px; }
                  h1, h2, h3 { line-height: 1.25; }
                  h1.entry-title { font-size: 22px; margin-bottom: 4px; }
                  .meta { color: #6b7080; font-size: 12px; margin-bottom: 24px; }
                  code, pre { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
                  pre { background: #f6f6fa; padding: 10px 12px; border-radius: 8px; overflow-x: auto; }
                  code { background: #f6f6fa; padding: 1px 5px; border-radius: 4px; }
                  pre code { background: none; padding: 0; }
                  table { border-collapse: collapse; width: 100%%; }
                  th, td { border: 1px solid #e5e5ee; padding: 6px 10px; text-align: left; }
                  blockquote { margin: 0; padding-left: 12px; border-left: 3px solid #e5e5ee; color: #6b7080; }
                </style>
                </head>
                <body>
                  <h1 class="entry-title">%s</h1>
                  <div class="meta">%s</div>
                  %s
                </body>
                </html>
                """.formatted(escapeHtml(entry.name()), escapeHtml(entry.name()), escapeHtml(entry.description()), bodyHtml);
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
