# Pumpkin Support — IntelliJ IDEA Plugin

Adds first-class IDE support for **Pumpkin Processes**: `@pumpkin`-tagged Cucumber/Gherkin Scenarios invokable via the `* Process: ...` step syntax.

The plugin **augments** IntelliJ's existing Gherkin/Cucumber support. It does not replace it, add a new language, or interfere with normal Cucumber step definitions.

---

## Building and installing

### Prerequisites

| Tool | Version |
|------|---------|
| JDK  | 17 or 21 |
| IntelliJ IDEA (to run the plugin) | 2024.1 – 2024.3 |
| Gradle | bundled via `./gradlew` wrapper |

### Build from source

```bash
# Clone / open the project
cd pumpkin-plugin

# Build the plugin ZIP
./gradlew buildPlugin

# The installable ZIP is produced at:
# build/distributions/pumpkin-plugin-<version>.zip
```

### Install

1. In IntelliJ IDEA: **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Select `build/distributions/pumpkin-plugin-<version>.zip`
3. Restart IntelliJ IDEA.

### Run in a sandboxed IDE (development)

```bash
./gradlew runIde
```

This launches a second IntelliJ IDEA instance with the plugin pre-installed.

---

## Configuration

### Process directories

Open **Settings → Tools → Pumpkin**.

Add one or more directories that contain your process feature files.  
Paths may be **absolute** or **relative to the project root**.

Default: `src/test/resources/processes`

All `.feature` files found **recursively** under the configured directories are indexed.

### Highlight colors

Open **Settings → Editor → Color Scheme → Pumpkin**.

Two attributes are configurable:

| Attribute | Applied to |
|-----------|-----------|
| `Process keyword // Process:` | The `Process:` prefix in an invocation step |
| `Process variable value` | The concrete values bound to process variables |

---

## Process definition

A Pumpkin Process is a regular Gherkin Scenario tagged with `@pumpkin`.

```gherkin
Feature: Customer Processes

  @pumpkin
  @processRequired(service,name)
  Scenario: Create customer {customerName}

    Given the customer service is available
    When I create the customer
    Then the customer exists
```

- `{customerName}` — a **process variable** whose value is supplied by the caller.
- `@processRequired(service,name)` — declares **required table parameters**.

> **Note:** Scenario Outlines are intentionally excluded from Process discovery
> because Cucumber's Examples table conflicts with the Pumpkin variable model.

---

## Process invocation

Invoke a process from any other feature file:

```gherkin
Scenario: Create a test customer

  * Process: Create customer Hans
    | service | 25736      |
    | name    | Hans Peter |
```

Value bindings:

| Name | Value |
|------|-------|
| `customerName` | `Hans` |
| `service` | `25736` |
| `name` | `Hans Peter` |

---

## Navigation

Place the caret on the invocation text after `Process: ` and press:

- **Mac**: `Option + click` (or `Cmd + B` / `Cmd + click`)
- **Windows / Linux**: `Alt + click` (or `Ctrl + B` / `Ctrl + click`)

The IDE navigates directly to the `@pumpkin` Scenario definition.

If **multiple** Pumpkin Processes match the invocation text, IntelliJ shows a "Choose Declaration" popup listing all candidates.

---

## Completion

Inside any Gherkin step line, type the Process keyword followed by part of the process name:

```
* Process: Cr
```

IntelliJ's completion popup shows all matching `@pumpkin` Scenarios.  
Selecting a suggestion:

1. Inserts the process invocation with variable placeholders removed (cursor positioned to type the first value).
2. Automatically appends table rows for every `@processRequired` parameter.

Example – selecting `Create customer {customerName}` with `@processRequired(service,name)` produces:

```gherkin
* Process: Create customer 
    | service |  |
    | name    |  |
```

---

## Validation and quick fixes

The **"Missing Pumpkin Process required parameter"** inspection runs in the editor and on-the-fly.

```gherkin
* Process: Create customer Hans
    | service | 25736 |
```

If `name` is required, IntelliJ underlines the step and reports:

> Pumpkin Process is missing required parameter: name

**Quick fix**: press `Alt + Enter` and choose:

- **Add missing Pumpkin Process parameter 'name'** — inserts the missing row.
- **Add missing Pumpkin Process parameters: …** — inserts all missing rows at once.

The fix appends the missing rows to the existing table without modifying existing values.

---

## Architecture decisions

| Decision | Rationale |
|----------|-----------|
| Depend on `org.jetbrains.plugins.cucumber` | All Gherkin PSI classes (`GherkinFile`, `GherkinScenario`, `GherkinStep`, `GherkinTable`, etc.) live in this bundled plugin. We extend it rather than replace it. |
| `PsiPolyVariantReferenceBase` for navigation | Allows IntelliJ to show a native disambiguation popup when multiple processes match, at no extra cost. |
| `SmartPsiElementPointer` in `PumpkinProcessDefinition` | Survives PSI tree invalidation across background indexing and file saves. |
| VFS `BulkFileListener` for cache invalidation | Fires after any file change on the IntelliJ VFS, ensuring the cache stays consistent with unsaved edits saved to disk. |
| Scenario Outlines excluded | Outlines use an Examples table for parameterisation, which conflicts with the `@processRequired` parameter table used by Pumpkin. |
| No custom Gherkin parser | All PSI traversal is done through `PsiTreeUtil` and the existing Gherkin PSI API. |
