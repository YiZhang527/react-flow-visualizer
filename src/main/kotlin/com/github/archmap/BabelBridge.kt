package com.github.archmap

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** JSON shape produced by [archmap-analyze/analyze.mjs]. */
data class BabelRoot(
    val version: Int = 1,
    val fileSummaries: Map<String, BabelFileSummary> = emptyMap(),
    val edges: List<BabelEdgeDto> = emptyList()
)

data class BabelFileSummary(
    val usages: List<BabelUsage> = emptyList(),
    val passesDown: List<BabelPassDownDto> = emptyList()
)

data class BabelUsage(
    val tag: String = "",
    val resolved: String? = null,
    val props: List<BabelProp> = emptyList()
)

data class BabelProp(val name: String = "", val kind: String = "data")

data class BabelPassDownDto(
    val prop: String = "",
    val kind: String = "data",
    val toRel: String = "",
    val toLabel: String = "",
    val toTag: String = ""
)

data class BabelEdgeDto(val source: String = "", val target: String = "", val kind: String = "")

object BabelBridge {

    private val log = Logger.getInstance(BabelBridge::class.java)

    /**
     * Runs the bundled Node analyzer ([analyze.bundle.cjs]) on [projectRoot].
     * Requires `node` on PATH. Returns raw JSON or null if skipped / failed.
     */
    fun run(projectRoot: File): String? {
        val stream = BabelBridge::class.java.getResourceAsStream("/archmap/analyze.bundle.cjs") ?: run {
            log.warn("ArchMap: analyze.bundle.cjs missing from plugin resources")
            return null
        }
        val dir = File(System.getProperty("java.io.tmpdir"), "archmap-js")
        dir.mkdirs()
        val script = File(dir, "analyze.bundle.cjs")
        try {
            stream.use { input ->
                Files.copy(input, script.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            log.warn("ArchMap: failed to extract analyzer", e)
            return null
        }
        return try {
            val pb = ProcessBuilder("node", script.absolutePath, projectRoot.canonicalPath)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val code = proc.waitFor()
            if (code != 0) {
                log.warn("ArchMap: node analyzer exited $code\n${out.take(500)}")
                return null
            }
            out.trim().takeIf { it.startsWith("{") }
        } catch (e: Exception) {
            log.warn("ArchMap: node not found or analyzer failed: ${e.message}")
            null
        }
    }
}
