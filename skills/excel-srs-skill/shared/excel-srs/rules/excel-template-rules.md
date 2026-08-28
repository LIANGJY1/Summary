# Shared Default Template Rules

## Scope

This file is the sole shared truth source for the default Excel template's schema, sheet model, enum values, writable semantics, and functional-sheet behavior.

## Default Template

- Template workbook: `/home/liang/Project/Reachauto/AI/demo/软件需求规格说明书-简化版.xlsx`
- This is the default template for `excel-srs-skill`.

## Template Integrity Rules

1. Always preserve workbook structure.
2. Treat comments, validations, visible headings, and named ranges as part of the template contract.
3. Preserve merged cells and protected sheet boundaries unless a template-sanctioned fill action requires extending a writable block.
4. Broken named ranges must not be blindly trusted.

## Sheet Model

The default Excel template must contain these sheets in order:

1. `变更履历`
2. `1.文档概述`
3. `2.软件系统概述`
4. `3功能需求`
5. `4.非功能性需求`
6. `附录-信息定义`

## Enum Rules

### Status Lifecycle

- `Draft`
- `In review`
- `Modified`
- `Approved`
- `Released`
- `Deleted` where supported by the requirement sheets

### CAL

- `-`
- `CAL1`
- `CAL2`
- `CAL3`
- `CAL4`

### Verifiability

- `Yes`
- `No`

### Verification Method

- `Test`
- `Review`

### Priority

- `H`
- `M`
- `L`
- `N/A`

## Placeholder Rules

The template allows these explicit placeholders only where the field semantics permit them:

- `无`
- `-`
- `No`
- `待确认`
- `假设`

The system must not use placeholders to hide missing required content.

## Fill Boundaries

1. The system must use Chinese body content by default.
2. The system must keep bilingual template headings unchanged.
3. Requirement wording must remain verifiable and reviewable.
4. Verification criteria must remain structured and testable.
5. Final workbook values must satisfy enum validation constraints.
6. The system must write only into template-approved writable regions.
7. The system must not overwrite formulas, instruction cells, locked cells, or validation controls.
8. The system may extend repeated row blocks only when the template layout clearly supports copyable rows.

## Change History Rules

- The system must add one generated entry to the change history when workbook filling is performed.
- The system must preserve existing history rows and formatting.

## Functional Sheet Semantics

For `3功能需求`, the system must apply the following semantics:

1. `一级需求` is a functional capability group, not a row-unique label.
2. One `一级需求` may correspond to multiple `二级需求` rows.
3. Each `二级需求` row must still carry its own:
   - `SWRD-ID`
   - requirement description
   - verification criteria
   - metadata fields required by the template
4. Repeating the same `一级需求` text across adjacent rows in the same capability group is the correct fill behavior.
5. Do not create artificial new Level 1 labels merely to avoid repetition.
6. Create a new Level 1 group only when the semantics move to a different capability group.

## Functional Sheet Merge Rules

1. When multiple adjacent writable rows belong to the same Level 1 capability group, the system should vertically merge the `一级需求` cells after the row values are written.
2. Merge behavior must be limited to the functional data block.
3. The system must never merge across unrelated groups, non-adjacent rows, header rows, instruction regions, or outside the writable block.
4. The system must never disturb existing header merges or unrelated workbook structure.

## Functional Row Expectations

- `SWRD-ID`, requirement description, verification criteria, risk, priority, and related metadata remain row-level fields at Level 2 granularity.
- Grouping affects only the Level 1 hierarchy presentation, not row-level traceability.

## Template-Specific Compliance Expectations

- Required sheets must exist.
- Enum-backed fields must use allowed values.
- Functional grouping and merge behavior must preserve both readability and traceability.
