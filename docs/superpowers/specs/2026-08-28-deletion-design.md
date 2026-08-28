# Deletion: entries, folders, tasks, projects

Date: 2026-08-28
Status: approved in brainstorming, ready for implementation plan

## Problem

The dashboard is entirely read-only today (a deliberate earlier design choice for `agent_tasks`,
carried through to everything else) - there is no way to remove clutter: stray demo entries, a
throwaway folder, a whole test task, or an entire scratch project. `memory_delete` exists as an
MCP tool for entries, but nothing else is deletable at all, from either the dashboard or MCP, and
even `memory_delete` has no REST wrapper so the dashboard can't use it.

## Decisions from brainstorming

1. **Scope: all four levels** - memory entries (any type, including `REPORT`), folders, whole
   tasks, whole projects.
2. **Available from both the dashboard (new capability) and new MCP tools** - a human doing
   manual cleanup and an agent doing it on request are both real use cases.
3. **Protection: an ordinary "are you sure?" confirm dialog**, not a type-the-name-to-confirm
   flow - for every level, including task/project. The dialog states what will be cascade-removed
   (counts where cheaply available) so "are you sure" isn't a blind click.

## 1. Cascade behavior - already correct at the DB level, verified against the migrations

No new FK work needed. Confirmed by reading every migration (V1-V8):

| Deleting a... | ...cascades to | ...via |
|---|---|---|
| `Task` | its `memory_nodes` (task-scoped entries) | `memory_nodes.task_id ON DELETE CASCADE` |
| `Task` | its `folders` (task-scoped folders) | `folders.task_id ON DELETE CASCADE` |
| `Task` | its `agent_tasks` (subtask board) | `agent_tasks.task_id ON DELETE CASCADE` |
| `Folder` | its subfolders | `folders.parent_id ON DELETE CASCADE` |
| `Folder` | entries directly in it (or in a cascaded subfolder) - **un-filed to root, NOT deleted** | `memory_nodes.folder_id ON DELETE SET NULL` |
| `MemoryNode` | its graph edges (both directions) | `memory_edges.source_id`/`target_id ON DELETE CASCADE` |
| `AgentTask` | dependents' `dependsOnId` pointer - **un-blocked, not deleted** | `agent_tasks.depends_on_id ON DELETE SET NULL` |

This means `TaskService.delete`/`FolderService.delete` are each a single-row JPA delete - Postgres
does the rest. Only project-level delete needs to touch multiple tables itself, because a
"project" isn't a table row (see below).

## 2. What each new delete operation does

- **Entry** (`MemoryService.delete(name)` - already exists, unused by REST/UI): delete the
  `memory_nodes` row. Edges clean up via cascade already. No new service code.
- **Folder** (`FolderService.delete(name)` - new): delete the `folders` row. Subfolders cascade;
  entries directly inside (recursively) are un-filed to root, not deleted - state this plainly in
  the confirm dialog so it isn't a surprise.
- **Task** (`TaskService.delete(projectScope, taskKey)` - new, distinct from the existing
  `task_close` which only flips status to `DONE`): delete the `tasks` row. Its entries, folders,
  and agent-task board all cascade away with it.
- **Project** (`ProjectService.delete(projectScope)` - new): a project has no row of its own
  (`ProjectService.list()` already derives it from distinct `project_scope` values). Deleting one
  means, in order: delete every `Task` with that `project_scope` (cascades away everything scoped
  to each), then delete remaining common-scope (`task IS NULL`) `memory_nodes`, then remaining
  common-scope `folders`, then `usage_events` for that `project_scope` (no FK, just analytics
  cleanup, but "и т.д." from the request covers it). One `@Transactional` method, four repository
  calls.

## 3. New MCP tools

- `folder_delete(projectScope, taskKey?, name)` - mirrors `memory_delete`'s shape.
- `task_delete(projectScope, taskKey)` - separate from `task_close`; description makes clear this
  is permanent and different from closing.
- `project_delete(projectScope)` - the most destructive tool in the whole server. Its
  `@McpTool` description must instruct the calling agent to **always get explicit user
  confirmation before calling it**, the same pattern already used for `task_start`'s "ALWAYS ask
  the user explicitly" - not a technical gate, but the established convention in this codebase for
  communicating "don't infer this silently."
- Entries need no new tool - `memory_delete` already exists and already does the job; only the
  dashboard-facing REST endpoint is missing.

## 4. Dashboard: read-only no longer, for delete only

Every other write already goes through MCP only, by design (`agent_tasks` in particular was
built explicitly read-only on the dashboard). Delete is a deliberate, scoped exception: cleanup
is a human/administrative action, not part of any agent workflow this dashboard automates -
create/edit stays MCP-only everywhere, only delete gets a UI.

- New REST: `DELETE /api/memory/{name}`, `DELETE /api/folders/{name}`,
  `DELETE /api/projects/{projectScope}/tasks/{taskKey}`, `DELETE /api/projects/{projectScope}`.
- New shared `ConfirmDialog.vue` component (no modal/dialog primitive exists in this codebase
  yet) - title, message (slot or prop, so cascade-impact text can be entity-specific), confirm/
  cancel, used the same way at every one of the 4 call sites rather than four bespoke `confirm()`
  calls.
- **Delete lives on detail pages only, not on list cards.** `EntryCard.vue`/`FolderCard.vue`/
  `TaskCard.vue`/`ProjectCard.vue` are today each a single `RouterLink` wrapping the whole card -
  nesting an interactive delete button inside would reintroduce the exact click-bubbling problem
  already hit and fixed once this session on `AgentTaskCard` (the toggle had to live outside the
  card's own click target). Detail-page-only avoids that class of bug entirely and needs no card
  restructuring: `EntryView.vue` (entries), `FolderView.vue` (folders), `TaskView.vue` (tasks),
  `ProjectView.vue` (projects - it has its own page at `/p/:project`, contrary to an earlier draft
  of this spec that claimed otherwise). Each gets a "Delete" action in its `PageHeader`'s
  `#actions` slot, the same slot already used for the existing "Graph"/"Back" buttons.
- After a successful delete, navigate away from the now-gone resource: entry -> its task page if
  task-scoped else its project page; folder -> the same `backLink` `FolderView.vue` already
  computes for its own "Back" button; task -> its project page; project -> the projects list
  (`/`).

## What's explicitly out of scope

- Soft-delete / trash / undo - these are hard, permanent deletes, matching how `memory_delete`
  already behaves today.
- Bulk multi-select delete (checkboxes + "delete N selected") - one target at a time per dialog.
- Any change to `create`/`update` staying MCP-only - this spec only adds a delete capability to
  the dashboard, nothing else becomes writable from the UI.
- Any change to `task_close`'s existing behavior.
