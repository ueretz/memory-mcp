---
name: memory-mcp
description: Use whenever you learn a durable user preference, receive corrective feedback, discover a project-specific fact, or want to check prior context before starting work in a repo that has the memory-mcp MCP server connected. Also use at the start of any substantive task to determine whether work is task-scoped. Call the memory_*, task_*, and folder_* MCP tools instead of writing memory files under ~/.claude/projects/.../memory/ or anywhere in the local repository.
---

# memory-mcp: Postgres-backed long-term memory

This project has the `memory-mcp` MCP server connected, giving you a database-backed
alternative to Claude Code's built-in flat-file memory. Entries live in Postgres, are
organized like a real filesystem (project → common context or per-task space → optional
nested folders), and are visible in a local dashboard - so prefer these tools over writing
memory markdown files by hand, and never write them into the local repository (see below).

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

## FORBIDDEN: writing generated files into the local repository

It is **forbidden**, with no exceptions besides the one below, to `Write` a file *you*
generated - your own notes, a plan, a summary, an analysis, a review writeup, a changelog
draft, a checklist, a design doc, a report - anywhere inside this local repository (or any
other local path standing in for it, e.g. a scratch/temp directory used as a substitute for
committing it). This is not "prefer memory," not "usually" - it is a hard, unconditional
prohibition. Save it via `memory_save` instead, every time. This applies everywhere, not just
mid-task: brainstorming, planning, answering a question, reviewing code, anything. If you catch
yourself reaching for `Write`/`Edit` to create a file for something that isn't code the user
asked you to ship, stop - this rule fires, no matter how small or "temporary" the file feels.

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

## Folders - organizing entries within a project or task

Entries can optionally be filed into a folder, an arbitrarily-nestable grouping scoped to a
project's common space or to one task (a folder can't span both). Folders exist purely for
organization when a project/task accumulates enough entries that a flat list gets unwieldy -
most work doesn't need them.

- Folders are created only by you, via `folder_create` - there is no UI for creating them, only
  for browsing. Call `folder_list` first to check whether a suitable folder already exists
  before creating a near-duplicate.
- Pass `folder` on `memory_save` to file an entry into an existing folder. The folder must
  already exist (via `folder_create`) and must belong to the exact same `projectScope`/`taskKey`
  as the entry itself - a folder from a different project or task is rejected.
- **Browsing (`memory_list`) treats folders like a real file explorer**: omitting `folder`
  lists only the root of that project/task scope - entries filed into a folder are hidden from
  the root listing and only show up when you pass that folder's name. This means after filing
  entries into a folder, call `memory_list` with `folder` set (or `folder_list` for the
  subfolders) to see them again, not the bare root call.
- **Searching (`memory_search`) does not have this restriction** - by default it searches every
  entry in the project/task scope regardless of folder, because search is for finding things,
  not browsing a location. Pass `folder` on `memory_search` only if you want to narrow the
  search to one specific folder (non-recursive - it won't also search that folder's subfolders).

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

- `memory_save(name, type, description, content, projectScope?, taskKey?, folder?, filePath?, createdBy?)` -
  upsert by name. `content` may reference other entries via `[[other-entry-name]]` - these become
  graph links. For `type: "LOCATION"`, use the fully-qualified class name as `name`. For
  `type: "REPORT"`, `content` is a full self-contained HTML document instead of markdown. Pass
  `folder` to file the entry under an existing folder (see "Folders" above) - omit to save it at
  the root of its project/task scope. Pass `createdBy` every time, resolved as described above.
- `memory_get(name)` - full content of one entry, plus what it links to/from.
- `memory_list(type?, projectScope?, taskKey?, folder?, limit?, offset?)` - cheap index (no
  content). With `projectScope` and no `taskKey`, lists the project's common entries. With both,
  lists that task's entries. Omitting `folder` lists only that scope's root - entries filed into
  a folder need `folder` set to show up (see "Folders" above). Call this before `memory_get` on
  everything - don't burn tokens reading full content you don't need yet.
- `memory_search(query, type?, projectScope?, taskKey?, folder?, limit?)` - full-text search,
  same cheap shape as `memory_list`, ranked by relevance. Unlike `memory_list`, omitting `folder`
  searches every entry in scope regardless of folder - pass `folder` only to narrow the search to
  one specific folder (non-recursive).
- `memory_graph(type?, projectScope?, taskKey?)` - nodes + edges for a scope, used by the
  dashboard and useful for understanding how entries relate. Always shows every entry in scope
  regardless of folder.
- `memory_related(name, depth?)` - entries directly linked to/from one entry - cheaper than
  `memory_get` when you just need to know what's connected.
- `memory_delete(name)` - remove a stale or wrong entry.
- `folder_create(projectScope, taskKey?, name, description, parentFolder?, createdBy?)` - create
  or update a folder (see "Folders" above). Idempotent by name - calling again with the same name
  updates its description/parent, but its project/task scope can't be changed once set. Pass
  `parentFolder` to nest it inside another folder (must already exist, same project/task scope).
- `folder_list(projectScope, taskKey?, parentFolder?)` - list folders directly under a project's
  common space, a task, or another folder. Omitting `parentFolder` lists top-level folders. Use
  before `folder_create` to avoid duplicates.
- `task_start(projectScope, taskKey, title?, source?)` - create or resume a task.
- `task_list(projectScope)` - list a project's tasks; check before creating a duplicate.
- `task_close(projectScope, taskKey)` - mark a task done.
- `location_scan(projectScope, rootPath)` - index a project's files/classes as `LOCATION`
  entries (see "Code locations" above).
- `agent_task_create(projectScope, taskKey, title, type, description?)` - create a subtask on a
  task's agent task board (`type`: `ANALYSIS`/`IMPLEMENTATION`/`TESTING`/`REVIEW`/`REPORTING`).
  Not idempotent - check `agent_task_list` first to avoid duplicates. See the `agent-task-board`
  skill for how these get driven end to end.
- `agent_task_list(projectScope, taskKey, type?, status?)` - list a task's subtasks, optionally
  filtered by category or status.
- `agent_task_update(projectScope, taskKey, agentTaskId, status?, title?, description?)` - move a
  subtask's status (`TODO`/`IN_PROGRESS`/`DONE`/`BLOCKED`) and/or update its notes.
- `agent_task_delete(projectScope, taskKey, agentTaskId)` - remove a stale/duplicate subtask.
