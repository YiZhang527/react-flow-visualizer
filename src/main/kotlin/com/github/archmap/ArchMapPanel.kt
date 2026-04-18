package com.github.archmap

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.BorderLayout
import java.io.File
import javax.swing.JPanel

class ArchMapPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(ArchMapPanel::class.java)
    private val browser: JBCefBrowser = JBCefBrowser()
    private val navigateQuery: JBCefJSQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
    private val tempDir = File(System.getProperty("java.io.tmpdir"), "archmap-webview")

    init {
        navigateQuery.addHandler { request ->
            handleNavigationRequest(request)
            null
        }
        add(browser.component, BorderLayout.CENTER)
    }

    fun showHtml(html: String) {
        tempDir.mkdirs()
        val bridgeJs = """
            window.__cefNavigate = function(request) {
                ${navigateQuery.inject("request")}
            };
        """.trimIndent()
        val fullHtml = html.replace("/*__BRIDGE__*/", bridgeJs)
        val file = File(tempDir, "archmap.html")
        file.writeText(fullHtml)
        browser.loadURL(file.toURI().toString())
    }

    fun showGraphView(graphDataJson: String) {
        tempDir.mkdirs()

        val dataFile = File(tempDir, "graph-data.js")
        dataFile.writeText("window.__graphData = $graphDataJson;")
        log.info("ArchMap: wrote graph-data.js (${dataFile.length()} bytes)")

        extractResource("cytoscape.min.js")

        val bridgeJs = """
            window.__cefNavigate = function(request) {
                ${navigateQuery.inject("request")}
            };
        """.trimIndent()

        val html = this::class.java.getResource("/webview/archmap-graph.html")?.readText()
        if (html != null) {
            val fullHtml = html.replace("/*__BRIDGE__*/", bridgeJs)
            val file = File(tempDir, "archmap-graph.html")
            file.writeText(fullHtml)
            browser.loadURL(file.toURI().toString())
            log.info("ArchMap: loaded graph view")
        } else {
            log.error("ArchMap: archmap-graph.html not found in resources")
        }
    }

    private fun extractResource(name: String) {
        val stream = this::class.java.getResourceAsStream("/webview/$name") ?: return
        stream.use { input ->
            File(tempDir, name).outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun handleNavigationRequest(request: String) {
        try {
            val gson = com.google.gson.Gson()
            val obj = gson.fromJson(request, JsonObject::class.java)
            val filePath = obj.get("filePath")?.asString ?: return
            val line = obj.get("line")?.asInt ?: 0

            ApplicationManager.getApplication().invokeLater {
                val vf = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return@invokeLater
                val descriptor = OpenFileDescriptor(project, vf, line, 0)
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            }
        } catch (e: Exception) {
            log.warn("Navigation failed: ${e.message}")
        }
    }

    fun dispose() {
        navigateQuery.dispose()
        browser.dispose()
    }
}
