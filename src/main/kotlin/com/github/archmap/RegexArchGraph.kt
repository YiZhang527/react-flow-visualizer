package com.github.archmap

import com.google.gson.Gson
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Builds Cytoscape graph JSON from a regex scan of import statements (no PSI / AST).
 */
object RegexArchGraph {

    private val gson = Gson()

    private val SKIP_DIRS = setOf(
        "node_modules", ".git", ".svn", "dist", "build", ".next", ".nuxt",
        "__pycache__", ".venv", "venv", ".idea", ".vscode", "coverage", ".cache"
    )

    private val EXT = setOf("js", "jsx", "mjs", "cjs", "ts", "tsx")

    private val IMPORT_RE = Regex(
        """(?:import\s+.*?from\s+['"](.+?)['"]|import\s*\(\s*['"](.+?)['"]\s*\)|require\s*\(\s*['"](.+?)['"]\s*\))"""
    )

    data class GraphMeta(
        /** All matching source files walked (js/ts variants). */
        val filesScanned: Int,
        /** Nodes currently shown (participate in at least one resolved import edge). */
        val filesInGraph: Int,
        /** Directed edges: source file imports target file. */
        val importEdges: Int
    )

    data class GraphPayload(
        val nodes: List<GraphNodeJson>,
        val edges: List<GraphEdgeJson>,
        val meta: GraphMeta
    )

    data class GraphNodeJson(
        val id: String,
        val label: String,
        val role: String,
        val roleDetail: String,
        val filePath: String,
        val relPath: String,
        val receivesProps: List<String> = emptyList(),
        val exports: List<String> = emptyList(),
        val renders: List<NodeRef> = emptyList(),
        val usedBy: List<NodeRef> = emptyList()
    )

    data class NodeRef(val id: String, val label: String)

    data class GraphEdgeJson(val source: String, val target: String)

    private data class Row(val fromRel: String, val module: String, val toRel: String?)

    private data class ScanResult(val rows: List<Row>, val filesScanned: Int)

    fun buildJson(project: Project): String {
        val root = project.basePath
            ?: return gson.toJson(GraphPayload(emptyList(), emptyList(), GraphMeta(0, 0, 0)))
        val base = File(root).canonicalPath
        val scan = scanProject(File(base), base)
        val rows = scan.rows
        val edgePairs = rows.mapNotNull { r -> r.toRel?.let { r.fromRel to it } }.toSet()
        if (edgePairs.isEmpty()) {
            return gson.toJson(GraphPayload(emptyList(), emptyList(), GraphMeta(scan.filesScanned, 0, 0)))
        }

        val nodeIds = mutableSetOf<String>()
        for ((a, b) in edgePairs) {
            nodeIds.add(a)
            nodeIds.add(b)
        }

        val outMap = mutableMapOf<String, MutableSet<String>>()
        val inMap = mutableMapOf<String, MutableSet<String>>()
        for ((s, t) in edgePairs) {
            outMap.getOrPut(s) { mutableSetOf() }.add(t)
            inMap.getOrPut(t) { mutableSetOf() }.add(s)
        }

        val edges = edgePairs.map { GraphEdgeJson(it.first, it.second) }.sortedWith(compareBy({ it.source }, { it.target }))

        val nodes = nodeIds.map { rel ->
            val f = File(base, rel)
            val label = stemLabel(f)
            val (role, roleDetail) = classify(rel, label)
            val filePath = f.absolutePath
            val renders = outMap[rel]?.map { tid ->
                NodeRef(tid, stemLabel(File(base, tid)))
            }?.sortedBy { it.label } ?: emptyList()
            val usedBy = inMap[rel]?.map { sid ->
                NodeRef(sid, stemLabel(File(base, sid)))
            }?.sortedBy { it.label } ?: emptyList()
            GraphNodeJson(rel, label, role, roleDetail, filePath, rel, emptyList(), emptyList(), renders, usedBy)
        }.sortedWith(compareBy({ it.role }, { it.label }))

        return gson.toJson(
            GraphPayload(nodes, edges, GraphMeta(scan.filesScanned, nodes.size, edges.size))
        )
    }

    private fun stemLabel(f: File): String {
        val n = f.name
        val dot = n.lastIndexOf('.')
        return if (dot > 0) n.substring(0, dot) else n
    }

    private fun classify(rel: String, stem: String): Pair<String, String> {
        val l = rel.replace('\\', '/').lowercase()
        val entryish = stem.equals("main", ignoreCase = true) || stem.equals("index", ignoreCase = true)
        return when {
            entryish && (l.endsWith(".jsx") || l.endsWith(".tsx") || l.endsWith(".js") || l.endsWith(".ts")) ->
                "entry" to "Entry"
            stem.endsWith("Page", ignoreCase = true) -> "page" to "Page"
            l.contains("config") -> "config" to "Config"
            else -> "component" to "Component"
        }
    }

    private fun scanProject(dir: File, projectRoot: String): ScanResult {
        val allFiles = mutableListOf<File>()
        dir.walkTopDown()
            .onEnter { it.name !in SKIP_DIRS }
            .filter { it.isFile && it.extension.lowercase() in EXT }
            .forEach { allFiles.add(it) }

        val relSet = allFiles.map {
            it.path.removePrefix(projectRoot).trimStart('/', '\\').replace('\\', '/')
        }.toSet()

        val rows = mutableListOf<Row>()
        for (file in allFiles) {
            val fromRel = file.path.removePrefix(projectRoot).trimStart('/', '\\').replace('\\', '/')
            val text = try {
                file.readText()
            } catch (_: Exception) {
                continue
            }
            for (m in IMPORT_RE.findAll(text)) {
                val mod = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
                val toRel = if (mod.startsWith(".")) resolveRel(fromRel, mod, relSet) else null
                rows.add(Row(fromRel, mod, toRel))
            }
        }
        return ScanResult(rows, allFiles.size)
    }

    private fun resolveRel(fromRel: String, importPath: String, all: Set<String>): String? {
        val fromDir = fromRel.substringBeforeLast("/", "")
        val base = if (fromDir.isEmpty()) importPath.removePrefix("./")
        else "$fromDir/${importPath.removePrefix("./")}"
        val norm = normalizePath(base)
        for (ext in listOf("", ".js", ".jsx", ".ts", ".tsx", ".mjs")) {
            val r = "$norm$ext"
            if (r in all) return r
        }
        for (idx in listOf("/index.js", "/index.jsx", "/index.ts", "/index.tsx")) {
            val r = "$norm$idx"
            if (r in all) return r
        }
        return null
    }

    private fun normalizePath(p: String): String {
        val parts = mutableListOf<String>()
        for (s in p.split('/')) {
            when (s) {
                ".", "" -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(s)
            }
        }
        return parts.joinToString("/")
    }
}
