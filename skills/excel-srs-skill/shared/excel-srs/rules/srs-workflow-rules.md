# Shared SRS Workflow Rules

## Scope

These rules define the shared process, governance, validation, and failure-handling behavior for turning natural-language requirements into a completed Excel SRS workbook.

These rules apply across projects and are loaded automatically by the skill.

## Authority Boundary

- This file governs shared workflow behavior, validation policy, artifact hygiene, precedence, and failure handling.
- Template-specific schema, sheet semantics, enum values, and writable-boundary rules must live in the shared template rules.
- `SKILL.md` is the runtime entrypoint and workflow summary. It does not override rule authority.

## Core Principles

1. Treat the workbook as a rule-driven specification container, not a blank spreadsheet.
2. The user should only need to trigger the skill; the system must load rules and complete the workflow proactively.
3. Reusable cross-project behavior belongs in the shared bundle, not in ad hoc project instructions.
4. Generated intermediate artifacts should remain confined to one run folder.
5. Ambiguity must be surfaced explicitly instead of being silently invented away.

## Instruction Precedence

When instructions conflict, the system must resolve them in this order:

1. user explicit request
2. skill runtime instructions
3. project or domain scenario rules
4. shared template rules
5. shared workflow rules
6. descriptive helper documents

Additional interpretation rules:

- `SKILL.md` summarizes execution flow, but policy authority remains in the rule files.
- Shared template rules override shared workflow rules only for template-specific behavior.
- Scenario rules may extend or narrow shared behavior, but they must not violate user intent or runtime safety constraints.

## End-to-End Workflow

### 1. Intake

- Read the natural-language requirements.
- Infer scenario or domain context when the input reasonably supports that inference.
- Determine whether the expected deliverable is a final workbook or an intermediate planning output.

### 2. Rule Loading

- Load the shared workflow rules.
- Load the shared template rules.
- Load optional project- or domain-specific scenario rules if they exist.
- Load the runtime skill instructions.
- The user must not have to mention these documents manually.

### 3. Workbook Discovery

1. Never rely on direct text reading of `.xlsx`.
2. Use workbook-safe inspection methods.
3. Confirm or extract:
   - sheet names and order
   - visible section boundaries
   - validations
   - comments
   - merged cells
   - named ranges
   - protected sheets and fields

### 4. Requirement Normalization

Normalize the natural-language requirements into a structured requirement model that can include:

- business goals
- actors or users
- system overview
- functional requirements
- non-functional requirements
- constraints
- assumptions
- risks
- open issues
- provenance or traceability

Normalization rules:

- Write confirmed information as facts.
- Write unconfirmed information as assumptions or open issues.
- If requirements conflict, record the conflict explicitly.
- If the input is materially incomplete, return clarification questions instead of inventing detail.

### 5. Mapping

- Map normalized requirements to workbook sections before writing.
- Keep one canonical source per requirement.
- Conform enum fields to template validations.
- Use explicit placeholders only where the template rules permit them.
- Defer field-level and sheet-level semantics to the shared template rules.

### 6. Filling

- Fill a workbook copy, not the original template.
- Change values only unless a structural edit is explicitly required by the template rules.
- Preserve workbook structure, comments, validations, merges, and layout.

### 7. Validation

- Reopen the workbook after generation.
- Check sheet list, key cells, and enum compliance.
- Run an external open or smoke test when possible.
- Do not treat the workbook as final if required values are missing or validations are violated.

## Decision Rules

1. If unsafe workbook inspection is attempted, the system must switch immediately to a workbook-safe method.
2. If a field conflicts with template validation, the system must rewrite it only when the intended meaning is preserved.
3. If ambiguity exists, the system must record it instead of inventing facts.
4. If workbook filling is requested, drafts and JSON outputs are intermediate artifacts, not the final delivery.
5. If workbook behavior contradicts the shared rules, the system must report the conflict explicitly instead of silently normalizing it away.

## Artifact Hygiene Rules

1. Normal generation uses only one run folder:
   - `.sisyphus/runs/<run-id>/`
2. Keep intermediate artifacts inside that folder only when needed, such as:
   - raw prompt
   - glossary
   - structured requirements
   - workbook fill draft
   - open issues
   - summary
   - manifest
3. Do not create extra `drafts/`, `plans/`, `one-shot-demo/`, or scenario folders during routine one-shot generation unless the user explicitly asks for them.

## Failure Handling Rules

1. **Template missing**
   - Stop immediately.
   - Report the missing template path.
   - Do not substitute a different workbook unless the user explicitly requests it.

2. **Required sheet missing**
   - Stop workbook generation.
   - Report which required sheet is absent.
   - Mark the template as structurally invalid.

3. **Broken named ranges or invalid workbook structure**
   - Continue discovery only if writable areas can still be identified safely.
   - Otherwise stop and report structural corruption.

4. **Enum validation mismatch**
   - Rewrite to a valid enum only if the intended meaning is preserved.
   - If the meaning would materially change, record the issue instead of forcing a value.

5. **Merge failure in the functional requirements block**
   - Keep row-level data intact.
   - Deliver unmerged rows if data correctness is preserved.
   - Report formatting degradation explicitly.

6. **Protected or locked write target**
   - Do not bypass protection.
   - Skip the write and report the blocked field.

7. **Ambiguous or underspecified requirement input**
   - Record assumptions and open questions.
   - Do not fabricate details.

8. **Validation failure after generation**
   - Do not mark the workbook as final.
   - Return the output as a draft with explicit failure reasons.
