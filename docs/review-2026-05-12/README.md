# Code review — 2026-05-12

This directory captures the output of a multi-agent review pass run after
PR #14 (ROM startup menu + playable DonkeyKong) and PR #15 (RomCatalog
auto-discover) merged to `master`. The reviews cover core impl, desktop
impl, core tests, desktop tests, and the path to a JS/web build.

## Branch

Work for the post-review cleanup happens on **`chore/review-cleanup`**,
forked off `master` at commit `bd82b7e` (the #15 merge commit).

## Layout

- [`triage.md`](triage.md) — the action plan. Tier A / B / C with
  checkboxes. **Drive all work from this doc.**
- `reports/` — full review output from each agent. Read these for the
  reasoning and line-number evidence behind any specific item.
  - [`reports/core-impl.md`](reports/core-impl.md)
  - [`reports/desktop-impl.md`](reports/desktop-impl.md)
  - [`reports/core-tests.md`](reports/core-tests.md)
  - [`reports/desktop-tests.md`](reports/desktop-tests.md)
  - [`reports/web-deployment.md`](reports/web-deployment.md)

## Tier overview

| Tier | What | Where |
|---|---|---|
| **A** | ~10 small, mechanical fixes — bug fixes + test corrections. Do on this branch. | [triage.md § Tier A](triage.md#tier-a--do-on-chorereview-cleanup-this-branch) |
| **B** | Correctness improvements requiring more care. Separate PR(s) after A merges. | [triage.md § Tier B](triage.md#tier-b--separate-prs-after-tier-a) |
| **C** | Roadmap: web build, CPU dispatch refactor, advanced PPU features. Pick up when ready. | [triage.md § Tier C](triage.md#tier-c--roadmap) |

## How to resume after compaction

If you're a future session picking this up:

1. `git checkout chore/review-cleanup` (or whichever branch is current).
2. Read [`triage.md`](triage.md) — it has the current state of each
   Tier A item (`[ ]` pending / `[x]` done).
3. Continue with the next unchecked item. Each finding cites its source
   file + line range, so the work is concrete.
4. After every committed fix, update the checkbox in `triage.md`. Treat
   that file as the ground truth for "what's left."
5. Tier B/C live in the same doc — only start them after Tier A is fully
   checked off and merged.

## Reproducing the reviews

Each report was produced by a worktree-isolated Opus agent, prompted with
specific scope and a strict read-only constraint. The prompts live in the
git history (find them by `git log --all --grep="Review:"` or look at the
commit that introduced this README). Re-running is rarely necessary —
prefer to update findings in place if new evidence appears.
