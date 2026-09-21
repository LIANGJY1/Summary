---
name: excel-srs-skill
description: Turn natural-language requirements into a completed Excel-based software requirements specification while preserving workbook structure and validation behavior.
argument-hint: Provide only the natural-language requirements. The skill will load the shared rule bundle, inspect the workbook, map the requirements, generate the output, and validate the result automatically.
allowed-tools:
  - read
  - glob
  - grep
  - bash
---

# Excel SRS Skill

## Purpose

This skill turns the user's natural-language requirements into a completed Excel SRS workbook based on the default Excel template and the shared rule bundle.

## Package Layout

- `SKILL.md`
  - Single entrypoint for runtime behavior, workflow summary, and output expectations.
- `shared/excel-srs/rules/srs-workflow-rules.md`
  - Shared governance, precedence, validation policy, and failure handling.
- `shared/excel-srs/rules/excel-template-rules.md`
  - Default template truth source for sheet model, enums, writable boundaries, and functional-sheet behavior.
- `shared/excel-srs/rules/*-srs-rules.md`
  - Optional scenario supplements when a project or domain needs narrower behavior.
- `shared/excel-srs/bin/excel-srs`
  - Reusable CLI entrypoint for workbook inspection, patching, and validation.
- `shared/excel-srs/scripts/excel_srs_tool.py`
  - Standard-library workbook helper used by the CLI wrapper.

## Entry Contract

- The user should only need to provide natural-language requirements.
- The skill must automatically load the required shared rules and supporting documents.
- Unless the user explicitly requests a different workbook, the skill must use the default Excel template defined by the shared template rules.

## Required Loads

The skill runtime must read these documents before generation:

- `/home/liang/.config/opencode/skills/excel-srs-skill/shared/excel-srs/rules/srs-workflow-rules.md`
- `/home/liang/.config/opencode/skills/excel-srs-skill/shared/excel-srs/rules/excel-template-rules.md`

If packaged scenario rules exist under `shared/excel-srs/rules/*-srs-rules.md`, the skill may load the matching supplement when the scenario applies.

If project- or domain-specific scenario rules exist under `.sisyphus/rules/*-srs-rules.md`, the skill must load them as runtime supplements.

## Runtime Responsibilities

The skill must complete the following end-to-end generation flow:

1. discover the target workbook safely
2. inspect workbook structure and writable boundaries
3. normalize user requirements into a structured requirement model
4. map normalized content to workbook sections
5. fill a workbook copy
6. validate the result
7. return the final workbook path and summary

## Workflow Summary

The minimal operating sequence is:

1. load the shared workflow rules
2. load the shared template rules
3. load optional scenario rules if they exist
4. inspect the workbook safely
5. normalize the natural-language requirements
6. map normalized content to workbook sections
7. fill a workbook copy
8. validate workbook structure and enum compliance
9. deliver the workbook path and summary

The workflow rules remain the authority for governance, precedence, validation policy, and failure handling. The template rules remain the authority for workbook-specific behavior.

## Execution Stages

### 1. Discovery

- The skill must use workbook-safe inspection methods.
- The skill must confirm the template structure before writing.
- The skill must report structural abnormalities explicitly instead of ignoring them.

### 2. Extraction

- The skill must normalize the input into a structured requirement model.
- The skill must distinguish confirmed facts from assumptions, risks, and open questions.
- The skill must not invent unsupported details.

### 3. Mapping

- The skill must apply the shared workflow rules, the shared template rules, and any applicable scenario rules before writing.
- The skill must follow the shared template rules for field semantics, writable regions, enum handling, and functional-sheet behavior.

### 4. Filling

- The skill must fill a workbook copy rather than the original template.
- The skill must preserve workbook structure, validations, comments, merges, and protected regions unless an explicit rule permits otherwise.

### 5. Validation

- The skill must reopen the generated workbook and verify that it remains usable.
- The skill must validate required fields, workbook structure, and enum compliance.
- The skill must treat validation failure as a reportable output state, not as a silent success.

### 6. Delivery

- The skill must return the workbook path.
- The skill must summarize the extracted requirements, open questions, risks, and next-step recommendations.

## Supporting Utilities

- CLI wrapper: `shared/excel-srs/bin/excel-srs`
- Python helper: `shared/excel-srs/scripts/excel_srs_tool.py`

These utilities are low-level workbook helpers. They support workbook inspection, patch application, and basic workbook validation, but they do not replace the full skill-level workflow, rule loading, or semantic validation contract defined above.

Example usage:

```bash
bash /home/liang/.config/opencode/skills/excel-srs-skill/shared/excel-srs/bin/excel-srs inspect --template /path/to/软件需求规格说明书-简化版.xlsx
bash /home/liang/.config/opencode/skills/excel-srs-skill/shared/excel-srs/bin/excel-srs apply --patch patch.json --output /tmp/out.xlsx
bash /home/liang/.config/opencode/skills/excel-srs-skill/shared/excel-srs/bin/excel-srs validate --workbook /tmp/out.xlsx
```

Maintenance guidance:

- Put reusable policy in the shared workflow rules.
- Put template-specific truth in the shared template rules.
- Keep scenario-specific behavior in `shared/excel-srs/rules/*-srs-rules.md` only when it adds unique value.
- Keep this file as the single human-facing entrypoint for the skill package.

## Output Contract

The final response must include:

1. the template and scope used
2. the extracted core requirement summary
3. the output workbook path
4. open questions and risks
5. next-step notes, if needed

## Must Not Do

- Do not read a binary `.xlsx` file as plain text.
- Do not fabricate requirements.
- Do not replace structured extraction with freeform improvisation.
- Do not damage workbook structure.
- Do not remove validations, comments, or protection without explicit rule support.
- Do not bypass locked or protected regions.
- Do not ignore validation failures.

## Quality Standard

- The same input should produce stable, as-deterministic-as-possible output.
- The mapping between requirements and workbook fields should remain traceable.
- The result should be suitable for formal review: complete, reviewable, and structurally safe.
