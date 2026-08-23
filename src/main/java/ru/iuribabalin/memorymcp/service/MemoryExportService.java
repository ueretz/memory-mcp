package ru.iuribabalin.memorymcp.service;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;
import ru.iuribabalin.memorymcp.dto.MemoryEntryDetail;
import ru.iuribabalin.memorymcp.entity.MemoryNode;

/**
 * Turns a memory entry into a downloadable PDF or markdown file. REPORT entries already hold a
 * full HTML document (rendered as-is); every other type holds markdown, wrapped in a minimal
 * print stylesheet before going to {@link PdfRenderer}.
 */
@Service
public class MemoryExportService {

    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder().build();
    private final PdfRenderer pdfRenderer;

    public MemoryExportService(PdfRenderer pdfRenderer) {
        this.pdfRenderer = pdfRenderer;
    }

    public byte[] toPdf(MemoryEntryDetail entry) {
        String html = entry.type() == MemoryNode.Type.REPORT ? entry.content() : wrapMarkdown(entry);
        return pdfRenderer.renderToPdf(html);
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
