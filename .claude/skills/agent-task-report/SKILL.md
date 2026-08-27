---
name: agent-task-report
description: Use whenever you need to build and save a REPORT-type memory entry styled like the memory-mcp dashboard itself (sidebar nav, inline-SVG diagrams, dashboard color tokens) - typically invoked by the agent-task-board skill for its Phase 1 planning report or Phase 2 final report, but also usable standalone any time the user asks for "a report" / "write-up" / "summary document" in a repo with memory-mcp connected. Never write the report to a local file - always memory_save(type: "REPORT", ...).
---

# agent-task-report: build a dashboard-styled HTML report and save it to memory

This skill produces one self-contained HTML report and saves it as a `REPORT`-type memory entry,
so it renders as a real page in the memory-mcp dashboard (`/p/{project}/t/{task}/e/{name}/report`)
instead of living as a file anywhere. It's a focused, single-purpose skill - it does not decide
*when* a report is needed or manage any task lifecycle; the caller (often `agent-task-board`,
sometimes a direct user request) provides the content.

## FORBIDDEN: no local files - the report goes straight into `memory_save`

Never `Write`/`Edit` the filled report to a local file - not in the project, not in a scratch/temp
directory. Read `assets/agent_task_report_template.html`, build the filled HTML in your own working
context, and pass it directly as `memory_save`'s `content` argument. This is unconditional, with
the same single exception as the main `memory-mcp` skill: a real source file the user explicitly
asked for as part of the application.

## Inputs this skill needs from its caller

- A title and a short original description/context for the work being reported on.
- `reportKind`: `"planning"` (built before any implementation exists - describes what *will*
  happen) or `"final"` (built after everything is done - describes what *did* happen). If the
  caller doesn't specify, ask, or infer from context (has implementation already happened?).
- Section content: architecture, interaction/diagram notes, implementation steps or outcomes,
  test results, review findings, risks - whatever the caller has. Empty sections are fine for
  `reportKind: "planning"` (see below); for `reportKind: "final"` don't invent content for a
  section you have nothing for - say so plainly instead.
- `projectScope`, and `taskKey` if this report is scoped to a task (pass both to `memory_save` -
  omitting `taskKey` when the report is really about one task makes it unreachable from that
  task's page).

## Building the report

1. Read `assets/agent_task_report_template.html` into your own working context - never write it
   or the filled version to disk. Replace each placeholder exactly once: `{{TASK_TITLE}}`,
   `{{GENERATED_AT}}`, `{{TASK_DESCRIPTION}}`, `{{ARCHITECTURE_CONTENT}}`,
   `{{INTERACTION_CONTENT}}`, `{{IMPLEMENTATION_CONTENT}}`, `{{TESTS_CONTENT}}`,
   `{{REVIEW_CONTENT}}`, `{{IMPACT_CONTENT}}`, `{{VERIFICATION_CONTENT}}`, `{{RISKS_CONTENT}}`.
   Two sections have no placeholder at all - don't invent one for them: "Обзор" (built
   client-side from the other sections' stats) and "Критичные ошибки" (built client-side by
   scanning every other section for `.callout.sev-critical`/`.sev-high` and listing them with a
   jump-link back to where they actually live - just tag findings with the right severity class
   wherever they belong, in Код-ревью/Риски/etc., and this section fills itself).
2. **`reportKind: "planning"`** (nothing has been executed yet):
   - `{{TASK_TITLE}}` = `"План: " + title` (prefix it clearly as a plan, not a result).
   - `{{ARCHITECTURE_CONTENT}}` / `{{INTERACTION_CONTENT}}` = the real architecture and diagrams -
     this is the actual content of a planning report.
   - `{{IMPLEMENTATION_CONTENT}}` = the planned steps as a checklist (`<ol class="plan-steps">`) -
     describe what *will* be done, not what was done.
   - `{{TESTS_CONTENT}}`, `{{REVIEW_CONTENT}}`, `{{RISKS_CONTENT}}` = a short
     `<p>Будет заполнено на этапе исполнения.</p>` placeholder each.
3. **`reportKind: "final"`** (everything is done): fill every section with what actually
   happened - `{{IMPLEMENTATION_CONTENT}}` is the real outcome per step, not the plan;
   `{{TESTS_CONTENT}}`/`{{REVIEW_CONTENT}}`/`{{RISKS_CONTENT}}` get real content. `{{TASK_TITLE}}`
   has no "План:" prefix.
4. Content per section is plain HTML: headings, `<p>`, `<table>`,
   `<div class="callout sev-{critical|high|medium|low}"><span class="badge
   {critical|high|medium|low}">high</span> ...</div>` for flagged findings, `<ol
   class="plan-steps">` for step lists.
   - `{{TESTS_CONTENT}}` should explicitly state test-case coverage (what's covered, what isn't),
     not just pass/fail counts.
   - `{{IMPACT_CONTENT}}` ("Влияние на прод/продукт") - what this change touches in production:
     rollout risk, backward compatibility, data migrations, anything a deploy needs to account
     for. For `reportKind: "planning"`, describe anticipated impact; for `"final"`, the real one.
   - `{{VERIFICATION_CONTENT}}` ("На что обращать внимание при проверке") - a checklist for
     whoever verifies this task manually: what to click through, what edge cases to try, what
     would indicate something's wrong. For `reportKind: "planning"` this can be the planned test
     plan; for `"final"` it should reflect what was actually verified and what's still unverified.
5. **Diagrams - inline SVG/HTML only, never Mermaid/PlantUML/any JS diagram library** (the report
   is a `memory_save` tool-call argument generated token-by-token; a multi-MB library payload
   doesn't fit). Component/architecture diagrams -> `<div class="flow"><div class="flow-node">...
   </div><div class="flow-arrow">→</div>...</div>` (plain HTML, text wraps naturally, can't
   overflow). Sequence/interaction diagrams -> hand-built `<svg class="sequence" ...>` with
   lifelines and arrows: keep in-SVG `<text>` under ~24 characters (a method name, an HTTP
   verb+path, a short verdict) and put explanations in an `<ol class="diagram-notes">`
   immediately below the `</svg>`, one `<li>` per arrow. Space lifelines >= 170px apart and
   message rows >= 40px apart vertically; set `width`/`height` attributes equal to the `viewBox`
   size so it never gets force-shrunk - the template scrolls horizontally instead.
6. Save: `memory_save(name: "<slug>-planning-report" | "<slug>-completion-report", type:
   "REPORT", description: "<one-line summary>", content: <filled HTML>, projectScope, taskKey,
   createdBy)`. Tell whoever asked for the report the dashboard link
   (`http://<host>:<port>/p/<projectScope>/t/<taskKey>/e/<name>/report` if task-scoped, or
   `.../e/<name>/report` without the `/t/<taskKey>` segment for a project-level report), never a
   file path.
