package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.iuribabalin.memorymcp.dto.MemoryEntryDetail;
import ru.iuribabalin.memorymcp.entity.MemoryNode;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryExportServiceTest {

    @Mock
    private PdfRenderer pdfRenderer;

    @Test
    void reportPdfExportForcesLightThemeWhenAttributeAlreadyPresent() {
        MemoryExportService service = new MemoryExportService(pdfRenderer);
        MemoryEntryDetail entry = report("<!doctype html><html data-theme=\"dark\"><body>hi</body></html>");
        when(pdfRenderer.renderToPdf(anyString())).thenReturn(new byte[0]);

        service.toPdf(entry);

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(pdfRenderer).renderToPdf(htmlCaptor.capture());
        assertThat(htmlCaptor.getValue()).contains("data-theme=\"light\"").doesNotContain("data-theme=\"dark\"");
    }

    @Test
    void reportPdfExportInjectsLightThemeWhenAttributeMissing() {
        MemoryExportService service = new MemoryExportService(pdfRenderer);
        MemoryEntryDetail entry = report("<!doctype html><html><body>hi</body></html>");
        when(pdfRenderer.renderToPdf(anyString())).thenReturn(new byte[0]);

        service.toPdf(entry);

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(pdfRenderer).renderToPdf(htmlCaptor.capture());
        assertThat(htmlCaptor.getValue()).contains("<html data-theme=\"light\">");
    }

    @Test
    void reportPdfExportForcesEveryTabPanelVisible() {
        MemoryExportService service = new MemoryExportService(pdfRenderer);
        MemoryEntryDetail entry = report("""
                <!doctype html><html><head><style>.tab-panel { display: none; }</style></head>
                <body>
                  <div class="tabs"><button class="tab-btn">Plan</button></div>
                  <section class="tab-panel active">Overview</section>
                  <section class="tab-panel">Risks</section>
                </body></html>
                """);
        when(pdfRenderer.renderToPdf(anyString())).thenReturn(new byte[0]);

        service.toPdf(entry);

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(pdfRenderer).renderToPdf(htmlCaptor.capture());
        String rendered = htmlCaptor.getValue();
        assertThat(rendered).contains(".tab-panel, .tab-content, .tabpanel, [role=\"tabpanel\"] { display: block !important; }");
        assertThat(rendered).contains(".tabs, .tab-nav, [role=\"tablist\"] { display: none !important; }");
        // both panels still present in the markup - the override is what makes the hidden one show
        assertThat(rendered).contains("Overview").contains("Risks");
    }

    @Test
    void reportPdfExportShrinksOversizedDiagramsInsteadOfClippingThem() {
        MemoryExportService service = new MemoryExportService(pdfRenderer);
        MemoryEntryDetail entry = report("""
                <!doctype html><html><head></head>
                <body>
                  <div class="diagram"><svg width="1030" height="380"><text>wide diagram</text></svg></div>
                </body></html>
                """);
        when(pdfRenderer.renderToPdf(anyString())).thenReturn(new byte[0]);

        service.toPdf(entry);

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(pdfRenderer).renderToPdf(htmlCaptor.capture());
        String rendered = htmlCaptor.getValue();
        assertThat(rendered).contains(".diagram, pre { overflow: visible !important; }");
        assertThat(rendered).contains("svg { max-width: 100% !important; height: auto !important; }");
    }

    @Test
    void reportPdfExportInjectsTabOverrideBeforeBodyCloseWhenNoHeadTag() {
        MemoryExportService service = new MemoryExportService(pdfRenderer);
        MemoryEntryDetail entry = report("<!doctype html><html><body><section class=\"tab-panel\">x</section></body></html>");
        when(pdfRenderer.renderToPdf(anyString())).thenReturn(new byte[0]);

        service.toPdf(entry);

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(pdfRenderer).renderToPdf(htmlCaptor.capture());
        assertThat(htmlCaptor.getValue()).contains("<style>").contains("</body></html>");
    }

    @Test
    void reportPdfExportKeepsFlowBoxesAtomicAcrossPageBreaks() {
        MemoryExportService service = new MemoryExportService(pdfRenderer);
        MemoryEntryDetail entry = report("<!doctype html><html><body><div class=\"flow-node\">x</div></body></html>");
        when(pdfRenderer.renderToPdf(anyString())).thenReturn(new byte[0]);

        service.toPdf(entry);

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(pdfRenderer).renderToPdf(htmlCaptor.capture());
        assertThat(htmlCaptor.getValue()).contains(".flow-node, .flow-arrow, .flow-branch, .diagram, .stat-tile, .overview-link-card,");
        assertThat(htmlCaptor.getValue()).contains("break-inside: avoid; page-break-inside: avoid;");
    }

    @Test
    void reportHtmlExportReturnsRawContentUnmodified() {
        MemoryExportService service = new MemoryExportService(pdfRenderer);
        String content = "<!doctype html><html data-theme=\"dark\"><body><div class=\"tab-panel\">x</div></body></html>";
        MemoryEntryDetail entry = report(content);

        assertThat(service.toHtml(entry)).isEqualTo(content);
    }

    @Test
    void nonReportHtmlExportWrapsMarkdownAsHtml() {
        MemoryExportService service = new MemoryExportService(pdfRenderer);
        MemoryEntryDetail entry = new MemoryEntryDetail(
                "note", MemoryNode.Type.PROJECT, "desc", "# Heading\n\nbody text",
                null, null, null, null, null, Instant.now(), Instant.now(),
                List.of(), List.of(), List.of());

        String html = service.toHtml(entry);

        assertThat(html).contains("<h1>Heading</h1>").contains("body text").contains("<!doctype html>");
    }

    private MemoryEntryDetail report(String content) {
        return new MemoryEntryDetail(
                "report-entry", MemoryNode.Type.REPORT, "desc", content,
                null, null, null, null, null, Instant.now(), Instant.now(),
                List.of(), List.of(), List.of());
    }
}
