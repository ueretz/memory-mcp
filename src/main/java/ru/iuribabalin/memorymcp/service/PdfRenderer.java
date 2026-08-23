package ru.iuribabalin.memorymcp.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Renders HTML to PDF via headless Chromium. Playwright objects are bound to the thread that
 * created them, so the browser and every call into it are confined to one dedicated thread -
 * concurrent HTTP requests queue through {@link #renderToPdf}.
 */
@Component
public class PdfRenderer {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pdf-renderer");
        thread.setDaemon(true);
        return thread;
    });

    private Playwright playwright;
    private Browser browser;

    public byte[] renderToPdf(String html) {
        try {
            return executor.submit(() -> renderOnRendererThread(html)).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PdfRenderException pdfRenderException) {
                throw pdfRenderException;
            }
            throw new PdfRenderException("Failed to render PDF: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PdfRenderException("PDF rendering was interrupted", e);
        }
    }

    private byte[] renderOnRendererThread(String html) {
        ensureBrowser();
        try (Page page = browser.newPage()) {
            page.setContent(html);
            return page.pdf(new Page.PdfOptions()
                    .setFormat("A4")
                    .setPrintBackground(true)
                    .setMargin(new Margin().setTop("16mm").setBottom("16mm").setLeft("14mm").setRight("14mm")));
        }
    }

    private void ensureBrowser() {
        if (browser != null) {
            return;
        }
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        } catch (Exception e) {
            throw new PdfRenderException(
                    "Chromium isn't installed for PDF export - run `./gradlew installPlaywrightBrowsers` once, then retry.", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.submit(() -> {
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
        });
        executor.shutdown();
    }
}
