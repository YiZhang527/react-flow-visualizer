package com.github.archmap

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Hybrid graph: regex import edges + optional Babel JSX props/callback edges (scheme D).
 */
object RegexArchGraph {

    private val gson = Gson()
    private val log = Logger.getInstance(RegexArchGraph::class.java)

    private val SKIP_DIRS = setOf(
        "node_modules", ".git", ".svn", "dist", "build", ".next", ".nuxt",
        "__pycache__", ".venv", "venv", ".idea", ".vscode", "coverage", ".cache"
    )

    private val EXT = setOf("js", "jsx", "mjs", "cjs", "ts", "tsx")

    private val IMPORT_RE = Regex(
        """(?:import\s+.*?from\s+['"](.+?)['"]|import\s*\(\s*['"](.+?)['"]\s*\)|require\s*\(\s*['"](.+?)['"]\s*\))"""
    )

    data class GraphMeta(
        val filesScanned: Int,
        val filesInGraph: Int,
        val importEdges: Int,
        val propsEdges: Int = 0,
        val callbackEdges: Int = 0,
        /** Babel analyzer ran and JSON was parsed (Node on PATH, bundle present). */
        val babelOk: Boolean = false
    )

    data class GraphPayload(
        val nodes: List<GraphNodeJson>,
        val edges: List<GraphEdgeJson>,
        val meta: GraphMeta
    )

    data class ReceivedPropJson(
        val name: String,
        val kind: String,
        val fromRel: String,
        val fromLabel: String
    )

    data class PassDownJson(
        val prop: String,
        val kind: String,
        val toRel: String,
        val toLabel: String
    )

    data class GraphNodeJson(
        val id: String,
        val label: String,
        val role: String,
        val roleDetail: String,
        val filePath: String,
        val relPath: String,
        val receivesProps: List<ReceivedPropJson> = emptyList(),
        val passesDown: List<PassDownJson> = emptyList(),
        val exports: List<String> = emptyList(),
        val renders: List<NodeRef> = emptyList(),
        val usedBy: List<NodeRef> = emptyList()
    )

    data class NodeRef(val id: String, val label: String)

    data class GraphEdgeJson(
        val source: String,
        val target: String,
        val kind: String = "import"
    )

    private data class Row(val fromRel: String, val module: String, val toRel: String?)

    private data class ScanResult(val rows: List<Row>, val filesScanned: Int)

    fun buildJson(project: Project): String {
        val root = project.basePath
            ?: return gson.toJson(GraphPayload(emptyList(), emptyList(), GraphMeta(0, 0, 0)))
        val baseFile = File(root).canonicalFile
        val base = baseFile.path

        val scan = scanProject(baseFile, base)
        val importPairs = scan.rows.mapNotNull { r -> r.toRel?.let { r.fromRel to it } }.toSet()

        val babelJson = try {
            BabelBridge.run(baseFile)
        } catch (e: Exception) {
            log.warn("ArchMap BabelBridge", e)
            null
        }

        val babel = babelJson?.let { json ->
            try {
                gson.fromJson(json, BabelRoot::class.java)
            } catch (e: JsonSyntaxException) {
                log.warn("ArchMap: invalid babel JSON: ${e.message}")
                null
            }
        }

        val babelEdges = babel?.edges?.filter { it.kind == "props" || it.kind == "callback" } ?: emptyList()
        val importEdgeList = importPairs.map { GraphEdgeJson(it.first, it.second, "import") }
        val allEdges = importEdgeList + babelEdges.map { GraphEdgeJson(it.source, it.target, it.kind) }

        val nodeIds = LinkedHashSet<String>()
        importPairs.forEach { (a, b) ->
            nodeIds.add(a)
            nodeIds.add(b)
        }
        babelEdges.forEach { e ->
            nodeIds.add(e.source)
            nodeIds.add(e.target)
        }
        babel?.fileSummaries?.keys?.forEach { nodeIds.add(it) }
        babel?.fileSummaries?.forEach { (_, summary) ->
            summary.usages.forEach { u -> u.resolved?.let { nodeIds.add(it) } }
            summary.passesDown.forEach { p -> nodeIds.add(p.toRel) }
        }

        if (nodeIds.isEmpty()) {
            return gson.toJson(
                GraphPayload(
                    emptyList(),
                    emptyList(),
                    GraphMeta(scan.filesScanned, 0, 0, 0, 0, babel != null)
                )
            )
        }

        val outMap = mutableMapOf<String, MutableSet<String>>()
        val inMap = mutableMapOf<String, MutableSet<String>>()
        for ((s, t) in importPairs) {
            outMap.getOrPut(s) { mutableSetOf() }.add(t)
            inMap.getOrPut(t) { mutableSetOf() }.add(s)
        }

        val receives = mutableMapOf<String, MutableList<ReceivedPropJson>>()
        babel?.fileSummaries?.forEach { (fromRel, summary) ->
            val fromLabel = stemLabel(File(baseFile, fromRel))
            for (u in summary.usages) {
                val target = u.resolved ?: continue
                for (p in u.props) {
                    receives.getOrPut(target) { mutableListOf() }.add(
                        ReceivedPropJson(p.name, p.kind, fromRel, fromLabel)
                    )
                }
            }
        }

        val passesByFile = mutableMapOf<String, List<PassDownJson>>()
        babel?.fileSummaries?.forEach { (fromRel, summary) ->
            val list = summary.passesDown.map { pd ->
                PassDownJson(pd.prop, pd.kind, pd.toRel, pd.toLabel)
            }
            if (list.isNotEmpty()) passesByFile[fromRel] = list
        }

        val nodes = nodeIds.map { rel ->
            val f = File(baseFile, rel)
            val label = stemLabel(f)
            val (role, roleDetail) = classify(rel, label)
            val filePath = f.absolutePath
            val renders = outMap[rel]?.map { tid ->
                NodeRef(tid, stemLabel(File(baseFile, tid)))
            }?.sortedBy { it.label } ?: emptyList()
            val usedBy = inMap[rel]?.map { sid ->
                NodeRef(sid, stemLabel(File(baseFile, sid)))
            }?.sortedBy { it.label } ?: emptyList()
            val rec = receives[rel]?.sortedWith(compareBy({ it.fromRel }, { it.name })) ?: emptyList()
            val pdown = passesByFile[rel] ?: emptyList()
            GraphNodeJson(
                rel, label, role, roleDetail, filePath, rel,
                rec, pdown, emptyList(), renders, usedBy
            )
        }.sortedWith(compareBy({ it.role }, { it.label }))

        val sortedEdges = allEdges.sortedWith(compareBy({ it.kind }, { it.source }, { it.target }))

        val nProps = babelEdges.count { it.kind == "props" }
        val nCb = babelEdges.count { it.kind == "callback" }

        return gson.toJson(
            GraphPayload(
                nodes,
                sortedEdges,
                GraphMeta(
                    scan.filesScanned,
                    nodes.size,
                    importEdgeList.size,
                    nProps,
                    nCb,
                    babel != null
                )
            )
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
