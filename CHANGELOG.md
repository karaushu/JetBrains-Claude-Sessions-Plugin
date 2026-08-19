# Changelog

Notable changes to Claude Sessions. Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 1.2.0 — 2026-08-19

### Added

- Notifications now come only from sessions running in this IDE's own tabs. Claude's hooks report
  every session on the machine, so a turn finishing in the desktop app or in a terminal outside
  the IDE used to raise a notification here. Switch it back under
  **Settings | Tools | Claude Sessions**.
- Review notes now work in a **unified diff** as well as side by side. A unified viewer numbers
  its own interleaved document rather than the file, so notes are held in file lines and converted
  for drawing through the viewer's own strict line mapping. A hovered deletion offers no `+`: that
  line exists only in the before side, and there is nothing in the file for a note to hold on to.

### Changed

- **Enter** now adds a review note; **Option+Enter** or **Cmd+Enter** starts a new line. A note is
  a sentence or two, so the key under the finger is the one that finishes it.
- A round now tells the agent to answer in the language the note is written in. A note in Ukrainian
  came back answered in English, because nothing in the round said otherwise.
- Changed transcripts are scanned incrementally from the previous read's offset. Transcripts are
  append-only, so a live multi-megabyte session now costs its appended bytes per rescan, not the
  whole file; a file that shrank — a compaction — is rescanned from the start.
- `~/.claude.json` is re-parsed only when its size or mtime actually changed, and the git worktree
  registry is cached until its directory changes — both were re-read in full every few seconds.

### Fixed

- npm installs of Claude Code run under `node`, so every live session read as dead — and could not
  be stopped. The liveness check now also reads the process arguments, where the script path is.
- Worktrees registered with relative paths — git's default since 2.51 — were invisible: the
  pointers were resolved against the IDE process's working directory instead of the repository.
- With no usage figures to age-gate on — an API-key account never writes any — the auto-refresh
  spawned a full `claude -p /usage` process every 30 seconds. Attempts are now gated by the same
  interval, and clicking the widget during a background refresh no longer leaves the popup stuck
  on "Asking Claude…".
- Concurrent refreshes could leave the list showing another project's sessions for a few seconds
  after toggling "show all projects" off; refreshes are now serialised.
- Hook-derived session state was shared between threads without a lock, which could kill the
  refresh loop outright. After an IDE restart, sessions that died without a `SessionEnd` were also
  resurrected in whatever state they crashed in; primed state is now kept only for live processes.
- Truncating the hook event log could destroy events appended between the read and the truncation
  — a lost `Stop` left a session spinning forever. The log now resets only when quiet, and the
  size is re-checked through the same channel that truncates.
- A `Stop` event fired by a stop hook that asked Claude to continue flipped the session to idle
  mid-turn.
- The "Unknown location" heading's live count was refreshed against the wrong key and stuck at
  zero; in-place refreshes also rescanned the whole list once per heading.
- A draft note vanished for good when the agent rewrote the file under it, and a submitted draft
  anchored at the line its box was opened on rather than the line the box had moved to. Review
  hunks also kept capture-time numbering after re-anchoring, putting the `>` marker on the wrong
  row.
- "Send them anyway?" on a closed tab looped forever — a closed tab has nothing to type into and
  is now refused outright. A send that crashed mid-flight also left its notes stuck in "sent".
- A follow-up reply typed while a round was in flight closed the round before the agent answered,
  and the answer was never read. A reply flushed in two writes could likewise be lost across an
  IDE restart, because the persisted read position pointed into the half-written line.
- Review paths are now stated against the project root, so an agent whose working directory is a
  worktree edits the reviewed tree rather than its own copy.
- A notification's "Open Session" bypassed every resume guard and could seize a session another
  process still owned; it now goes through the same guarded path as the session list.
- Closing a project never released a terminal tab's process scope, and a tab closed and reopened
  quickly could delete the fresh tab's registration instead of the old one's.
- A `+` tab could adopt an unrelated project's session while "show all projects" was on.

## 1.1.0 — 2026-08-12

### Added

- **Review notes in the diff viewer.** Hover a line in a side-by-side diff and a `+` appears
  beside the line number; clicking it opens a box to write what needs fixing. Notes accumulate
  across lines and files, and a button at the top of the diff sends the whole set in one go: the
  label sends to the session it names, the arrow beside it picks another session or clears every
  note. Sending refuses outright while the target session is waiting on a permission prompt — a
  review typed into a prompt cannot be recalled — and asks first when it is mid-turn.
- **Replies under the line.** The round is written to
  `~/.claude/claudesessions/reviews/<project>/`, outside the project so it never appears in the
  commit being reviewed, and the agent is asked to append one line per note to a replies file.
  Each answer appears under the note it belongs to; `×` closes the note, and writing on it again
  reopens it and sends it in the next round with its whole history. A note the agent never
  answered stays visibly unanswered rather than being marked as done.
- Notes follow their code: the text of the line is stored with the note, so an agent editing the
  file moves the note rather than stranding it. A line that really has gone says so.
- Notes are kept in `.idea/workspace.xml`, so they survive closing the diff and restarting the
  IDE.

## 1.0.2 — 2026-07-29

### Fixed

- Icons drew at 40 px in list rows and editor tabs. `IconLoader` sizes an SVG from its
  `width`/`height` attributes rather than its `viewBox`, and the 1.0.1 icon change flattened
  all fourteen files to 40 — losing the 13 px stripe, 20 px new-UI stripe and 16 px row sizes
  the originals declared. Restored, and `IconResourcesTest` now asserts the declared size of
  every icon so this cannot pass silently again.

## 1.0.1 — 2026-07-29

### Changed

- The plugin is named **Sessions for Claude Code** — descriptive rather than possessive, since
  it works with Claude Code and is not a Claude product. The plugin id, tool window and
  notification groups keep their existing identifiers, so installed settings and saved layout
  are untouched.
- The icon is the [Claude AI symbol](https://commons.wikimedia.org/wiki/File:Claude_AI_symbol.svg)
  from Wikimedia Commons, released under CC0 1.0.
- Licensed [MIT](LICENSE), and the README and plugin description state plainly that this is
  unofficial and unaffiliated with Anthropic.

## 1.0.0 — 2026-07-29

First release. Nothing was published before this, so there is no upgrade path to describe.

### Added

- **Session list** in a tool window on the left stripe, in the IDE's own design language:
  two-line rows with the session title, working directory, git branch and last activity.
- **Live status per session** — an orange Claude mark when alive, a spinner while Claude is
  working, an attention icon when it wants input. The stripe icon is badged so a session
  needing you is visible while the tool window is collapsed.
- **Resume in an editor tab.** Clicking a session runs `claude --resume` in a tab in the main
  editor area, not in the Terminal tool window; clicking it again focuses that tab. `+` starts
  a new session, and links itself to the real session id once Claude creates one. Closing the
  tab ends the session, without a confirmation.
- **Grouping by project** with collapsible headings when showing sessions from all projects.
- **Git worktrees.** A session run in a worktree is listed under the project it was started
  for, marked with the worktree's name — including worktrees at arbitrary paths, read from
  git's own registry rather than inferred from a directory name.
- **Notifications** when a turn ends or Claude needs input, each switchable on its own. macOS
  shows them only while the IDE is not the active application; inside the IDE they arrive as
  balloons and persist in the Notifications tool window.
- **Usage limits** in the toolbar: the 5-hour percentage, with a dropdown for every window
  (5-hour, weekly all-models, weekly per-model). Read from the payload Claude already caches,
  so no API call and no credential handling.
- **Row menu** on hover with **Delete**, which moves a session to
  `~/.claude/claudesessions/archive/` and offers Undo, and **Stop** for a running session,
  which asks first and then terminates it.
- **Settings** under *Settings | Tools | Claude Sessions*, or the gear in the tool window:
  notifications, background usage fetching and its interval.
- **Status hooks** (opt-in, via *Claude Status Hooks* in Find Action) so status is reported
  for every Claude entrypoint rather than only interactive terminal sessions, and reported as
  it changes rather than on the next poll. Notifications depend on these.

### Fixed

Things that broke during development and are worth knowing were deliberate decisions, not
oversights:

- **Inherited `CLAUDE_CODE_*` markers are scrubbed.** An IDE launched from a Claude Code
  session carries `CLAUDE_CODE_CHILD_SESSION=1`, and the CLI then treats every session the
  plugin spawns as a nested child: no transcript, no pid file, invisible to the list.
- **A killed session no longer spins forever.** Hooks have no event for "killed", so a session
  interrupted mid-turn left a `UserPromptSubmit` with no `Stop` and sat at the top of the list
  with a spinner. Liveness now comes from the pid file alone.
- **The IDE's own agent is not listed as an abandoned session**, nor are the daemon's
  background jobs. Both look identical to a fresh terminal session without reading
  `entrypoint` and `kind`.
- **A background agent is offered what actually works** — attach or fork — rather than a
  `--resume` that refuses, since a session cannot have two owners.
- **Editor tab titles keep up** with the session they host, and a session tab survives being
  dragged towards a split.
- **The row-actions button** is reachable across the whole row, and no longer paints over the
  editor.
- No horizontal scrollbar in the session list.

### Known limitations

- An editor terminal tab does not survive an IDE restart; sessions stay resumable from the
  list. The platform's own terminal-in-editor has the same problem
  ([IJPL-107495](https://youtrack.jetbrains.com/issue/IJPL-107495)).
- Splitting a session tab is blocked by design: one Swing component has one parent.
- OS notifications cannot be verified from a `runIde` sandbox, which has no application bundle
  for `NSUserNotificationCenter` to attribute them to.
- A worktree that has since been deleted cannot be attributed to its project, and is reachable
  only through the all-projects view.

### Requirements

WebStorm 2026.2 (build 262+). Branch 262 needs a Java 25 toolchain, which Gradle provisions
automatically.
