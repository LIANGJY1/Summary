---
name: session-to-skill
description: Distill the current conversation into a reusable, structured skill (SKILL.md) and install it under .agents/skills/. Use whenever the user asks to summarize/turn this session or conversation into a skill — e.g. "把本轮会话总结成一个skill", "把这次讨论沉淀成技能", "make this session a skill", "turn our conversation into a skill", or says the session's workflow/learnings should become reusable — even if they don't use the word "skill" explicitly.
---

# Skill: session-to-skill

Turn what happened in *this* conversation into a durable skill the agent can re-run later.
You are not summarizing for a human reader (that's `handoff`); you are writing an *executable instruction set* for a future agent that was NOT present in this session.

## 1. Decide the skill type — and whether one exists

Mine the conversation for one of two shapes:

- **Workflow skill** — the session showed a repeatable *process* (a sequence of steps, artifacts produced, feedback loops). The skill re-runs the process.
- **Knowledge skill** — removed from this skill's output. Decisions, constraints, and domain knowledge are declarative assets: route them to the Summary knowledge-base or an ADR. A skill may reference those assets but must not duplicate them verbatim.

If the session was a one-off (single bug fix, no repeated pattern, no reusable knowledge), say so and stop — forcing it into a skill produces noise. Offer `handoff` or a dated summary doc instead.

Then scan existing skills (`~/.agents/skills/`, `<project>/.agents/skills/`, `.zcode` plugin cache) for one that already covers this. If found, propose editing that skill instead of creating a near-duplicate.

## 2. Do not duplicate what is already on disk

If the session's conclusions were already persisted (specs, ADRs, matrices, learning notes), reference them by absolute path in the skill body. The skill holds the *process/trigger*, the files hold the *content*.

## 3. Pick name and location

- Name: lowercase kebab-case, 1–64 chars, matches the directory name.
- Location: `<project>/.agents/skills/<name>/` if it only makes sense in this repo; `~/.agents/skills/<name>/` if the user wants it everywhere (default when unclear — check how the user phrased it).

## 4. Write the SKILL.md

Frontmatter: `name` + `description`. The description is the primary trigger — state what it does AND the contexts/phrasings that should fire it (include the user's actual language, Chinese phrases included). Slightly over-trigger is better than under-trigger.

Body, imperative and lean (< 500 lines):

- **Workflow skill**: ordered steps, when to stop/escalate, the artifact format with a literal example, and pointers (not copies) to reference material. Bundle helper scripts under `scripts/` if the session showed the same multi-step workaround being repeated.
- **Knowledge skill**: structured, append-friendly layout — stable prefixed IDs per category (e.g. `J1`, `C2`), one bold-line thesis + minimal bullets per entry, and maintenance rules: append next ID, never renumber, supersede via new entry ("supersedes J3"), link out to detail docs when an entry exceeds ~5 lines.

Structure example for the body's own template:

```markdown
## Steps
1. <imperative step — with why, if non-obvious>
2. ...

## Output format
<literal example of the artifact this skill produces>

## Edge cases
- <when to stop / escalate / say "not a skill">
```

## 5. Verify and report

- `name` matches directory name; description contains trigger phrasings including the user's language.
- Body has at least one literal example; no content duplicated from on-disk docs.
- Report to the user: installed path, skill type, trigger phrases, and 2–3 realistic test prompts (casual phrasing the user would actually type) to try in a fresh turn — per the skill-creator loop, iterate on those results if the trigger or output misses.
