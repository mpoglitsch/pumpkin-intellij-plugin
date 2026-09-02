---
name: pumpkin-plugin
description: Reference guide for the Pumpkin IntelliJ Plugin — architecture, key files, PSI patterns, and extension points. Load this before working on any plugin feature.
---

# Pumpkin IntelliJ Plugin — Architecture Reference

## Project overview

An IntelliJ IDEA plugin that adds IDE support for a test automation framework called "Pumpkin Processes". Feature files are Cucumber/Gherkin `.feature` files. The plugin is written in Java 17 and targets IntelliJ 2026.1 (build 261). Built with Gradle + `gradle-intellij-plugin` v1.x.

**Key dependencies (`gradle.properties`):**
```
platformType=IC, platformVersion=2026.1
platformPlugins=gherkin:261.22158.182,com.intellij.java
```

**Package root:** `com.pumpkin.intellij`

---

## Feature 1 — Process Navigation

Steps that look like `* Process: some process name` navigate to the Gherkin `@pumpkin`-tagged Scenario that defines that process.

### Key files

| File | Role |
|------|------|
| `repository/PumpkinProcessService.java` | `@Service` — project-scoped cache of all process definitions. Lazily loads on first access, invalidated on VFS changes. |
| `repository/PumpkinProcessRepository.java` | Scans configured process directories for `.feature` files. |
| `repository/PumpkinProcessParser.java` | Parses `GherkinFile` PSI, extracts `PumpkinProcessDefinition` (scenario + parameters). |
| `navigation/PumpkinGotoDeclarationHandler.java` | `GotoDeclarationHandler` registered with `order="first"`. Ctrl+Click on a Process step calls `PumpkinProcessService.findMatchingProcesses()`. |
| `navigation/PumpkinProcessReferenceContributor.java` | `PsiReferenceContributor` on `GherkinStep` — provides PSI references (Find Usages, etc.). |
| `navigation/PumpkinProcessReference.java` | `PsiPolyVariantReferenceBase<GherkinStep>` — resolves via the cached service. |
| `util/GherkinPsiUtil.java` | Helpers: `findEnclosingStep(PsiElement)`, `isProcessStep(GherkinStep)`, `getProcessInvocationText(GherkinStep)`. |
| `model/PumpkinProcessDefinition.java` | Holds the scenario PSI element and extracted metadata. |
| `highlighting/PumpkinProcessAnnotator.java` | Syntax highlighting for Process invocations. |
| `completion/PumpkinProcessCompletionContributor.java` | Completion suggestions after `Process: `. |
| `inspection/PumpkinProcessInspection.java` | Inspection: missing required process parameters. |

**Pattern:** cache-based, proactive discovery. The service scans at startup / on file change and makes navigation instant.

---

## Feature 2 — API Step Navigation & Inlay Hints

Steps matching `(I )send a/an {apiRequestDefinition} to {apiNotation} (including these/this parameter/parameters | without parameters)` navigate to the matching endpoint enum constant in the proxy class. An inlay tag above the line shows the HTTP method and path, and optionally the body template filename (clickable).

### Step pattern

```java
// ApiStepPattern.java
Pattern: (?:I\s+)?send\s+an?\s+(.+?)\s+to\s+([A-Z_][A-Z0-9_]*)\s+
         (?:including\s+(?:these|this)\s+parameters?|without\s+parameters)
Flags: CASE_INSENSITIVE
Group 1 = apiRequestDefinition  ("redis job start request")
Group 2 = apiNotation           ("BACKEND_API")
```

### Key FQNs in the target project

| Symbol | FQN |
|--------|-----|
| `ApiNotation` enum | `at.compax.rp.test.model.api.ApiNotation` |
| `AbstractApiProxy` | `at.compax.rp.test.services.api.proxy.AbstractApiProxy` |

### Resolution algorithm (`ApiEndpointResolver.java`)

**Strategy 1 (primary):** Search for references to `AbstractApiProxy` that appear in an `extends` clause → get each proxy class → call `extractGetApiNotation(proxy)` → match `apiNotation`.

**Strategy 2 (fallback):** Search all references to `ApiNotation.{apiNotation}` constant → filter for refs inside `return` of a method named `getApi` or inside a field named `api` (Lombok) → get containing class.

**`extractGetApiNotation(proxy)` supports two forms:**
1. Explicit method: `public ApiNotation getApi() { return ApiNotation.BACKEND_API; }`
2. Lombok `@Getter`: `@Getter private final ApiNotation api = ApiNotation.BACKEND_API;`

**`findEndpointConstant(proxy, apiRequestDefinition)`:** Searches inner enums of the proxy class. Matching: `normalise(constantName).equalsIgnoreCase(normalise(apiRequestDefinition))` where `normalise` trims, removes underscores, collapses whitespace.

### Key files

| File | Role |
|------|------|
| `api/ApiStepPattern.java` | Regex parser, returns `ParsedApiStep` with both captured groups and their `TextRange`. |
| `api/ApiEndpointResolver.java` | All resolution logic: endpoint lookup, template file discovery. |
| `api/ApiGotoDeclarationHandler.java` | `GotoDeclarationHandler` with `order="first"`. Handles any click on a matching step. Returns `null` (not `EMPTY_ARRAY`) on failure so Cucumber can fall back. |
| `api/ApiEndpointReferenceContributor.java` | `PsiReferenceContributor` on `GherkinStep`. |
| `api/ApiEndpointReference.java` | `PsiPolyVariantReferenceBase<GherkinStep>`. Has `DumbService.isDumb()` guard. |
| `api/ApiInlayHintsProvider.java` | `InlayHintsProvider<NoSettings>`. Block hint above matching steps: API tag + optional clickable template tag. |

### Template file discovery (`ApiEndpointResolver.findTemplateVirtualFile`)

Proxy classes have:
```java
@Value("classpath:api/templates/.../templateFile.json")
Resource templateFile;

public Optional<String> getBodyTemplate(EndpointEnum endpoint) {
    return switch (endpoint) {
        case REDIS_JOB_START_REQUEST -> Optional.of(loadPayloadTemplate(templateFile));
        default -> Optional.empty();
    };
}
```

Resolution: find `getBodyTemplate` → find switch block → match case label → find `loadPayloadTemplate(field)` call → resolve field → read `@Value` annotation → strip `classpath:` prefix → search module source roots.

Both **enhanced switch** (`case X ->`) and **traditional switch** (`case X:`) are supported.

The `VirtualFile` is resolved via `ModuleRootManager.getSourceRoots()` for all modules, then `root.findFileByRelativePath(path)`.

---

## Feature 3 — Section Folding

Gherkin files can have collapsible regions using:
```gherkin
# Section: My Section Name
... content ...
# End
```

### Key file

`section/GherkinSectionFoldingBuilder.java` — `FoldingBuilderEx`. Uses `FoldingGroup` so all segments of a parent section collapse/expand atomically. Child sections remain visible as separate placeholders even when the parent is collapsed (gap-segment approach).

**macOS quirk:** Gherkin lexer on macOS attaches a preceding `\n` to some tokens. `SectionNode.startOffset` uses `text.indexOf('#')` to skip it.

---

## plugin.xml extension points

```xml
<depends>com.intellij.modules.platform</depends>
<depends>com.intellij.modules.java</depends>
<depends>gherkin</depends>

<!-- Services -->
<projectService serviceImplementation="...PumpkinSettingsState"/>
<projectService serviceImplementation="...PumpkinProcessService"/>

<!-- Process navigation -->
<gotoDeclarationHandler implementation="...PumpkinGotoDeclarationHandler" order="first"/>
<psi.referenceContributor language="Gherkin" implementation="...PumpkinProcessReferenceContributor"/>

<!-- API navigation -->
<gotoDeclarationHandler implementation="...ApiGotoDeclarationHandler" order="first"/>
<psi.referenceContributor language="Gherkin" implementation="...ApiEndpointReferenceContributor"/>

<!-- API inlay hints -->
<codeInsight.inlayProvider language="Gherkin" implementationClass="...ApiInlayHintsProvider"/>

<!-- Folding -->
<lang.foldingBuilder language="Gherkin" implementationClass="...GherkinSectionFoldingBuilder"/>

<!-- Other -->
<annotator language="Gherkin" implementationClass="...PumpkinProcessAnnotator"/>
<completion.contributor language="Gherkin" implementationClass="...PumpkinProcessCompletionContributor" order="first"/>
<localInspection language="Gherkin" implementationClass="...PumpkinProcessInspection" .../>
<projectConfigurable ... instance="...PumpkinSettingsConfigurable"/>
<colorSettingsPage implementation="...PumpkinColorSettingsPage"/>
```

---

## SDK constraints and gotchas

- **`ClassInheritorsSearch`** is in `plugins/java/lib/modules/intellij.java.indexing.jar` which the Gradle IntelliJ plugin v1.x does NOT put on the compile classpath. Use `ReferencesSearch` as an alternative.
- **`FoldingDescriptor.EMPTY`** is deprecated — return `new FoldingDescriptor[0]` instead.
- **`PresentationFactory.text(String)`** renders at normal editor font — use for indentation prefix in block inlays to align with code below.
- **`PresentationFactory.referenceOnHover(presentation, clickListener)`** — adds hyperlink cursor + click handler. Called on EDT (mouse events), safe to call `FileEditorManager.openFile()` directly.
- **`InlayHintsSink.addBlockElement(offset, relatesToPreceding, showAbove, priority, presentation)`** — the presentation starts at the left edge; must manually prefix with `factory.text(indent)` to match code indentation.
- **Gherkin language ID** for extension points: `"Gherkin"` (capital G).
- **`GherkinStep.getTextOffset()`** returns the offset of the keyword start (after any leading whitespace). The indent is `document.getText(new TextRange(lineStart, stepOffset))`.

---

## Settings

`settings/PumpkinSettingsState.java` — persisted settings including process directories. Accessed via `PumpkinSettingsState.getInstance(project)`.

`settings/PumpkinSettingsConfigurable.java` — settings UI (registered under `parentId="tools"`, `displayName="Pumpkin"`).

---

## Source tree quick-reference

```
src/main/java/com/pumpkin/intellij/
├── api/
│   ├── ApiEndpointReference.java
│   ├── ApiEndpointReferenceContributor.java
│   ├── ApiEndpointResolver.java        ← resolution + template discovery
│   ├── ApiGotoDeclarationHandler.java
│   ├── ApiInlayHintsProvider.java      ← hint rendering + template tag
│   └── ApiStepPattern.java
├── completion/
│   └── PumpkinProcessCompletionContributor.java
├── highlighting/
│   ├── PumpkinColorSettingsPage.java
│   └── PumpkinProcessAnnotator.java
├── inspection/
│   └── PumpkinProcessInspection.java
├── model/
│   └── PumpkinProcessDefinition.java
├── navigation/
│   ├── PumpkinGotoDeclarationHandler.java
│   ├── PumpkinProcessReference.java
│   └── PumpkinProcessReferenceContributor.java
├── repository/
│   ├── PumpkinProcessParser.java
│   ├── PumpkinProcessRepository.java
│   └── PumpkinProcessService.java      ← cached service
├── section/
│   └── GherkinSectionFoldingBuilder.java
├── settings/
│   ├── PumpkinSettingsConfigurable.java
│   └── PumpkinSettingsState.java
└── util/
    └── GherkinPsiUtil.java
```
