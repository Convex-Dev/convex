---
name: issue-triage
description: Triage the open Convex issues against a fixed rubric — close what is done or superseded, give each a type label, and collect everything that needs a maintainer decision into one review sheet. Use when asked to review or triage all open issues.
---

# Issue triage

Triage keeps the open issues to outstanding bugs and enhancements. Apply the
same rubric on every pass so passes can be compared.

## Rubric

1. **Close** issues that are done or superseded: `--reason completed` when done;
   `--reason "not planned"` plus the `duplicate` label when superseded. Add a
   one-line comment citing the evidence (file, CAD or PR).
2. **`question`** for an unresolved question or ambiguous point. Post the
   question if the thread does not already state it.
3. **`bug`** only for an active defect that still needs fixing for the next
   release.
4. **`performance`** where optimisation or efficiency matters.
5. **`security`** where security matters.
6. **`enhancement`** for a request beyond current functionality.

Each open issue carries exactly one type label: `bug`, `enhancement` or
`question`. `performance` and `security` are extras. For areas use the existing
labels (`peer`, `convex-db`, `UCAN`, `operations`); create no new ones. No open
issue ends unlabelled.

Fix an issue directly only if the fix is genuinely quick, adds no complexity,
needs no design decision and touches no network state, consensus or core code;
flag it for review. `metadata.cvx` is part of the genesis state
(`Init.createBaseState`), so even a docstring typo there is not a quick fix.

## Procedure

1. List the open issues:
   `gh issue list --repo Convex-Dev/convex --state open --limit 500 --json number,title,labels`.
2. Fan verification out to read-only subagents by area, about 15 issues each,
   each writing its findings to a scratch file.
3. Verify every status claim against the code before acting on it: a target
   such as "v2" is stale while `Migrations.LIVE_VERSION` is still 0. Find the
   commits for an issue with `git log -P --grep='#NNN\b'`; without `-P` the `\b`
   is ignored and `#354` also matches `#3540`.
4. Act directly only on labels and on closures verified as completed. Put
   not-planned closures, consolidations and every comment to an external
   contributor into one numbered review sheet for the maintainer.
5. Report by action: closed, relabelled, commented, for review.

Never put exploit detail in a public comment: an edit keeps the original in the
issue's history. Exploitable vulnerabilities follow [SECURITY.md](../../../SECURITY.md).
