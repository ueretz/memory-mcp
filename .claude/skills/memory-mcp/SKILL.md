---
name: memory-mcp
description: Use whenever you learn a durable user preference, receive corrective feedback, discover a project-specific fact, or want to check prior context before starting work in a repo that has the memory-mcp MCP server connected. Also use at the start of any substantive task to determine whether work is task-scoped. Call the memory_* and task_* MCP tools instead of writing memory files under ~/.claude/projects/.../memory/.
---

# memory-mcp: Postgres-backed long-term memory

This project has the `memory-mcp` MCP server connected, giving you a database-backed
alternative to Claude Code's built-in flat-file memory. Entries live in Postgres, are
organized like a GitHub repo (project → common context + per-task folders), and are visible
in a local dashboard - so prefer these tools over writing memory markdown files by hand.

## Determine the project scope automatically - never ask for it

`projectScope` identifies the product/repo this work belongs to. Derive it yourself:
1. Prefer the git remote's repo name: `git remote get-url origin` (e.g.
   `git@github.com:org/memory-mcp.git` → `memory-mcp`).
2. If there's no remote, fall back to the current working directory's folder name.

Never ask the user what the project is - this should be invisible to them.

## Determine createdBy automatically - never ask for it

`createdBy` records who authored an entry. Resolve it yourself before every `memory_save`:
`git config user.name` and `git config user.email` in the current repo, combined as
`"Name <email>"`. If either is unset, pass whatever you have (or omit it) - don't prompt the
user for their identity.

## MANDATORY: any file you generate for the project goes in memory, not on disk

This is a hard rule, not a preference. Any time you're about to `Write` a **new** file that
*you* generated - your own notes, a plan, a summary, an analysis, a review writeup, a changelog
draft, a checklist, a design doc, a report - you are **required** to save it via `memory_save`
instead. Not "prefer to," not "usually" - required, every time, no exceptions besides the one
below. This applies everywhere, not just mid-task: brainstorming, planning, answering a
question, reviewing code, anything. If you catch yourself reaching for `Write`/`Edit` to create
a file for something that isn't code the user asked you to ship, stop - this rule fires.

1. **Markdown content** (notes, plans, summaries, analysis, checklists, writeups) ->
   `memory_save` with `type: "PROJECT"` (or task-scoped if a `taskKey` is set).
2. **A full report** (HTML, with tables/charts/styling - anything you'd otherwise hand the user
   as a standalone HTML file) -> `memory_save` with `type: "REPORT"` and `content` set to a
   **full self-contained HTML document**: inline all CSS/JS, no external CDN scripts or
   stylesheets, embed images as data URIs. Same constraint as an Artifact - it has to render
   correctly with nothing but the HTML itself. REPORT content is rendered as a real HTML page
   (sandboxed iframe), not markdown-parsed.
3. Tell the user it's saved and give them the dashboard link instead of a file path - never a
   path, always this:
   - project-level: `http://<host>:<port>/p/<projectScope>/e/<name>`
   - task-scoped: `http://<host>:<port>/p/<projectScope>/t/<taskKey>/e/<name>`
   - use the host/port the MCP server is actually running on (default `http://localhost:8080`).
   For REPORT entries, mention they can also download it as a PDF from that page (a
   "Download PDF" button backed by `GET /api/memory/{name}/pdf`, works for any entry type).

**The only exception:** the user explicitly asked for a file - a source file the app needs to
run, or a deliverable they said to commit/ship. If you're unsure whether something is "your own
notes/output" or a real requested deliverable, treat it as notes - default to memory.

## Determine task scope - always ask explicitly

At the start of any substantive piece of work, **always ask the user explicitly** whether
this work belongs to a specific task/ticket. Do not infer this silently from context, and do
not skip asking just because it seems obvious - it must be an explicit question every time.

- If the user says yes:
  1. Check whether a ticket-tracker MCP tool is available in the current session (e.g. a tool
     whose name suggests Jira or another issue tracker). If one exists, prefer using it to
     resolve the task's key and title (pass `source: "JIRA"` to `task_start`).
  2. Otherwise, ask the user directly for the task/ticket number (`source: "MANUAL"`).
  3. Call `task_start` with the resolved `projectScope`, `taskKey`, and `title`. This is
     idempotent - safe to call again if the task already exists (use `task_list` first if you
     want to check without creating anything).
  4. Save task-specific working notes via `memory_save` with both `projectScope` and
     `taskKey` set. This keeps the task's context in its own "folder", separate from the
     product's durable knowledge.
  5. When the task's work is done, call `task_close`.
- If the user says no: proceed without a `taskKey`. Anything you save is project-level
  "common" context (durable knowledge about the product, not tied to one unit of work).

## Answering questions - memory first, then ask before reading code

Whenever the user asks a question about the project or a task - "how does X work",
"give me context on task N", "what's the implementation of Y" - follow this order every time,
without skipping steps:

1. **Search memory first, before touching any source file.** Resolve `projectScope` (and
   `taskKey` if the question is about a specific task) and call `memory_search` / `memory_list`
   / `memory_get` for entries relevant to the question. Answer using whatever you find there.
   If nothing relevant exists, say so explicitly instead of silently falling through to the
   codebase.
2. **Always ask before reading the code - this is not optional and not inferable.** Regardless
   of whether memory had an answer, explicitly ask the user something like: "Нужно ли уточнить,
   как это реализовано в коде сейчас?" / "Want me to check how this is actually implemented in
   the code?". Do not decide on your own that the memory answer is "probably still right" or
   "probably good enough" - ask every time, the same way task scope is always asked explicitly.
   - If the user says no, stop - answer from memory alone.
3. **If the user says yes, read the code** to find the current implementation/logic, then
   reconcile it against what memory said:
   - If the code confirms the memory entry, leave memory as-is - do **not** re-save it. Saving
     an unchanged fact again just creates a duplicate.
   - If the code disagrees with memory (the implementation changed, or memory was wrong/stale),
     or memory had nothing on this at all, `memory_save` to update the existing entry in place
     (upsert by name) or create a new one - and tell the user memory was out of date and has
     been refreshed.
4. **Before saving anything, check it isn't already there.** Upserting by `name` prevents
   duplicate names, but the same fact can drift in under a different name. Before a `memory_save`
   that isn't a confirmed update from step 3, run `memory_search`/`memory_get` on the topic and
   compare content, not just the name - if an existing entry already says the same thing, update
   that entry (or skip) instead of writing a near-duplicate one.

## Maintaining common project context

Regardless of task scope, keep a small set of `PROJECT`-type entries with `projectScope` set
and **no** `taskKey` up to date - these describe durable facts about the product (architecture,
conventions, key decisions) and persist across all tasks, similar to a living README. Update
these when you learn something that will matter beyond the current task.

## Code locations - don't grep the filesystem, check the index first

Run `location_scan(projectScope, rootPath)` once per project (pass the project's real absolute
root path - don't rely on the MCP server process's own working directory, it isn't guaranteed
to match yours), or again after a significant restructuring. It walks the tree and indexes every
file as a `LOCATION` entry; for `.java` files it also detects the class and links its in-project
imports, so `memory_graph(type: "LOCATION", projectScope: ...)` shows a real class dependency map.

Before searching the filesystem for "where is X", check
`memory_search(query: "X", type: "LOCATION", projectScope: ...)` first - it's cheaper than a
filesystem search and usually already has the answer. After finishing a task or being pointed at
specific classes, you can also save/update a single location directly via
`memory_save(type: "LOCATION", filePath: ..., ...)` without waiting for a full re-scan.

## Categories

- **USER** - facts about the user's role, preferences, or how they want to collaborate.
- **FEEDBACK** - corrections or confirmations about how to approach work. Include *why*.
- **PROJECT** - durable facts about the product/codebase being built (see above).
- **REFERENCE** - pointers to where information lives in external systems.
- **LOCATION** - where a class or file lives in the project (see "Code locations" above).
- **REPORT** - a full self-contained HTML document (see "HTML reports" above), rendered as a
  real page in the dashboard rather than markdown.

## Tools

- `memory_save(name, type, description, content, projectScope?, taskKey?, filePath?, createdBy?)` -
  upsert by name. `content` may reference other entries via `[[other-entry-name]]` - these become
  graph links. For `type: "LOCATION"`, use the fully-qualified class name as `name`. For
  `type: "REPORT"`, `content` is a full self-contained HTML document instead of markdown. Pass
  `createdBy` every time, resolved as described above.
- `memory_get(name)` - full content of one entry, plus what it links to/from.
- `memory_list(type?, projectScope?, taskKey?, limit?, offset?)` - cheap index (no content).
  With `projectScope` and no `taskKey`, lists the project's common entries. With both, lists
  that task's entries. Call this before `memory_get` on everything - don't burn tokens reading
  full content you don't need yet.
- `memory_search(query, type?, projectScope?, taskKey?, limit?)` - full-text search, same cheap
  shape as `memory_list`, ranked by relevance.
- `memory_graph(type?, projectScope?, taskKey?)` - nodes + edges for a scope, used by the
  dashboard and useful for understanding how entries relate.
- `memory_related(name, depth?)` - entries directly linked to/from one entry - cheaper than
  `memory_get` when you just need to know what's connected.
- `memory_delete(name)` - remove a stale or wrong entry.
- `task_start(projectScope, taskKey, title?, source?)` - create or resume a task.
- `task_list(projectScope)` - list a project's tasks; check before creating a duplicate.
- `task_close(projectScope, taskKey)` - mark a task done.
- `location_scan(projectScope, rootPath)` - index a project's files/classes as `LOCATION`
  entries (see "Code locations" above).
