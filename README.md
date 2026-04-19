# React Flow Visualizer

Beginners opening a React repo often cannot quickly see how components nest and which file owns what. Reading files one by one takes time and does not give a clear mental map of the hierarchy, so onboarding and debugging feel slow and confusing.

A **JetBrains IDE** plugin built on the **IntelliJ Platform**. It scans React components in your project from a right-side tool window, renders a component graph with **D3**, and lets you jump to source by clicking nodes. The explanation panel is generated in the embedded page from heuristics.

## Features

- **Project analysis**: Walks `.js`, `.jsx`, `.ts`, and `.tsx` files under content roots and detects function and arrow-function components.
- **Component graph**: Infers parent–child links from `import` usage; prefers a component named `App` as the root, otherwise uses the first parsed component.
- **Node details**: Shows `useState` usage and a props list parsed from the function signature.
- **Graph UI**: Uses the embedded browser (JCEF) — click nodes to open sources, zoom, and toggle the side panel.

## Tech stack

| Item | Details |
|------|---------|
| Language | Kotlin (JVM 17) |
| Build | Gradle 8.5, `org.jetbrains.intellij.platform` 2.2.0 |
| Target IDE | IntelliJ IDEA Community **2023.2.1** (`sinceBuild = 232`, `untilBuild = 241.*`) |
| Visualization | Embedded browser loads `graph.html`; charting uses [D3 v7](https://d3js.org/) |

## Requirements

- **JDK 17**
- Gradle downloads the matching IntelliJ Platform when you run the sandbox IDE (see `create("IC", "2023.2.1")` in `build.gradle.kts`).

## Build and run

From the project root:

```bash
./gradlew buildPlugin
```

Distribution zip:

```bash
ls build/distributions/
```

Run a sandbox IDE with the plugin:

```bash
./gradlew runIde
```

In the sandbox: **View → Tool Windows → React Flow Visualizer**, then click **Analyze React Project**.

## Usage

1. Open a project that contains React sources (directories should be marked as source roots).
2. Open the **React Flow Visualizer** tool window.
3. Click **Analyze React Project**.
4. In the graph, click a component node to navigate to its definition; the side panel shows heuristic explanations (props, data flow, etc.).

## Project layout

```
├── build.gradle.kts          # Plugin + IntelliJ Platform config
├── settings.gradle.kts
├── gradle.properties
└── src/main
    ├── kotlin/com/github/reactflow/
    │   ├── MyToolWindowFactory.kt   # Tool window, JCEF, analysis, HTML injection
    │   ├── analysis/
    │   │   └── ComponentExtractor.kt
    │   ├── model/
    │   │   └── ComponentModel.kt    # ReactComponent, ComponentGraph
    │   └── agent/                   # Helpers / extensions
    └── resources
        ├── META-INF/plugin.xml
        └── graph.html               # D3 layout, clicks, side panel UI
```
