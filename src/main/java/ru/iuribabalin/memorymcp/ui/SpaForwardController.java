package ru.iuribabalin.memorymcp.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The dashboard is a Vue single-page app with history-mode routing, so a hard refresh on a
 * client-side route has to be answered with index.html. Prefixes are listed explicitly to keep
 * /api and /mcp out of the way - they must match the routes in ui/src/router/index.ts.
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/setup", "/p/**"})
    public String forward() {
        return "forward:/index.html";
    }
}
