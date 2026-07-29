# Changelog

Notable changes to Claude Sessions. Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
