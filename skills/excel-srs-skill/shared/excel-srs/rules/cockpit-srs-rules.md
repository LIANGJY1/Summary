# Cockpit SRS Scenario Rules

## Scope

This file defines scenario-specific supplements for intelligent automotive cockpit application software requirements specifications.

This file is a scenario supplement only. Shared workflow behavior, shared merge behavior, shared traceability requirements, and shared failure handling remain governed by the shared workflow rules and the shared template rules.

## Cockpit Functional Grouping Guidance

For cockpit-domain requirement sets, the system should interpret `一级需求` as a cockpit capability group rather than as a visually repeated label.

Examples of cockpit capability grouping can include, when supported by the source requirements:

- media and audio interaction
- navigation and travel assistance interaction
- communication and connectivity interaction
- vehicle status, settings, or control interaction
- voice, assistant, or multimodal interaction

The system should keep adjacent rows within the same `一级需求` group only when they describe the same cockpit capability group. Repeated wording alone does not justify grouping if the underlying capability differs.

## No Local Override of Shared Merge Rules

This scenario file does not redefine how adjacent `一级需求` cells are merged.

When cockpit requirements produce adjacent rows that belong to the same capability group, the system must apply the shared template merge rules as written. If safe merging is not possible, the system must follow the shared workflow failure-handling rules instead of introducing cockpit-specific fallback behavior.
