# Codex Agent Skills

This directory exposes Convex's repository skills through the Agent Skills open
format so Codex and compatible agents can discover them.

The canonical instructions and supporting resources live under
`.claude/skills/`. Each `.agents/skills/<name>/SKILL.md` file contains only
matching discovery metadata and a link to the corresponding canonical skill.
Agents must read and follow the canonical file before using a skill.

When adding, renaming or removing a skill:

1. Make the substantive change under `.claude/skills/`.
2. Add, rename or remove the matching forwarding entry under `.agents/skills/`.
3. Keep the forwarding entry's `name` and `description` aligned with the
   canonical skill.
4. Keep skill-specific Codex metadata, such as `agents/openai.yaml`, beside the
   forwarding entry when required.

Do not duplicate workflow instructions or supporting resources here; that would
allow the two discovery paths to drift.
