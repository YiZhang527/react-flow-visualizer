package com.github.reactflow.analysis

import com.github.reactflow.model.ComponentGraph
import com.github.reactflow.model.ReactComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.roots.ProjectRootManager

class ComponentExtractor(private val project: Project) {

    private val componentMap = mutableMapOf<String, ReactComponent>()
    private val importMap = mutableMapOf<String, MutableList<String>>()

    fun extract(): ComponentGraph {
        componentMap.clear()
        importMap.clear()

        val roots = ProjectRootManager.getInstance(project).contentSourceRoots
        roots.forEach { root -> scanDirectory(root) }

        // Build parent-child relationships
        for ((parentName, childNames) in importMap) {
            val parent = componentMap[parentName] ?: continue
            for (childName in childNames) {
                val child = componentMap[childName] ?: continue
                if (!parent.children.contains(child)) {
                    parent.children.add(child)
                }
            }
        }

        val root = componentMap["App"] ?: componentMap.values.firstOrNull()
        return ComponentGraph(root, componentMap)
    }

    private fun scanDirectory(dir: VirtualFile) {
        VfsUtilCore.iterateChildrenRecursively(dir, null) { file ->
            if (!file.isDirectory && (file.extension == "jsx" || file.extension == "js" || file.extension == "tsx" || file.extension == "ts")) {
                processFile(file)
            }
            true
        }
    }

    private fun processFile(file: VirtualFile) {
        val text = try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            return
        }

        val lines = text.lines()

        // Find React function components
        val componentRegex = Regex("""(?:export\s+(?:default\s+)?)?function\s+([A-Z][a-zA-Z0-9]*)\s*\(""")
        val arrowComponentRegex = Regex("""(?:export\s+(?:default\s+)?)?(?:const|let)\s+([A-Z][a-zA-Z0-9]*)\s*=\s*(?:\([^)]*\)|[a-zA-Z_$][a-zA-Z0-9_$]*)\s*=>""")

        val foundComponents = mutableListOf<Pair<String, Int>>()
        lines.forEachIndexed { index, line ->
            componentRegex.find(line)?.let { foundComponents.add(it.groupValues[1] to index + 1) }
            arrowComponentRegex.find(line)?.let { foundComponents.add(it.groupValues[1] to index + 1) }
        }

        if (foundComponents.isEmpty()) return

        // Find imports
        val importRegex = Regex("""import\s+(\w+)\s+from\s+['"]([./][^'"]+)['"]""")
        val importedComponents = mutableListOf<String>()
        importRegex.findAll(text).forEach { match ->
            val importedName = match.groupValues[1]
            if (importedName[0].isUpperCase()) {
                importedComponents.add(importedName)
            }
        }

        // Find props from function signature
        val propsRegex = Regex("""\{\s*([^}]+)\s*\}""")
        val props = mutableListOf<String>()
        foundComponents.firstOrNull()?.let { (_, lineNum) ->
            val funcLine = lines.getOrNull(lineNum - 1) ?: ""
            propsRegex.find(funcLine)?.let { match ->
                match.groupValues[1].split(",").forEach { prop ->
                    val trimmed = prop.trim().substringBefore(":").trim()
                    if (trimmed.isNotEmpty() && !trimmed.contains(" ")) {
                        props.add(trimmed)
                    }
                }
            }
        }

        for ((name, lineNum) in foundComponents) {
            // Find this component's body range
            val startLine = lineNum - 1
            val endLine = findBodyEnd(lines, startLine)

            // Check useState only within this component's body
            val bodyLines = lines.subList(startLine, minOf(endLine + 1, lines.size))
            val hasUseState = bodyLines.any { it.contains("useState") }

            componentMap.getOrPut(name) {
                ReactComponent(
                    name = name,
                    filePath = file.path,
                    lineNumber = lineNum,
                    hasUseState = hasUseState,
                    props = props
                )
            }

            if (importedComponents.isNotEmpty()) {
                importMap.getOrPut(name) { mutableListOf() }.addAll(importedComponents)
            }
        }
    }

    private fun findBodyEnd(lines: List<String>, startLine: Int): Int {
        var depth = 0
        var started = false
        for (i in startLine until lines.size) {
            for (char in lines[i]) {
                if (char == '{') { depth++; started = true }
                if (char == '}') depth--
            }
            if (started && depth <= 0) return i
        }
        return lines.size - 1
    }
}
