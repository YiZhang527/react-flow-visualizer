package com.github.reactflow

import com.github.reactflow.analysis.ComponentExtractor
import com.github.reactflow.model.ReactComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import javax.swing.*

class MyToolWindowFactory : ToolWindowFactory, DumbAware {

    private var componentMap: Map<String, ReactComponent> = emptyMap()

    private fun buildHtml(json: String): String {
        val graphHtml = javaClass.classLoader.getResource("graph.html")?.readText()
            ?: return "<p>graph.html not found</p>"
        return graphHtml.replace(
            "window.addEventListener('load', () => {",
            """
            window.addEventListener('load', () => {
              initGraph('${json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "")}');
              return;
            """.trimIndent()
        )
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val panel = JPanel(BorderLayout())
        val analyzeButton = JButton("Analyze React Project")

        if (!JBCefApp.isSupported()) {
            val outputArea = JTextArea("Click 'Analyze' to scan your React project.\n")
            outputArea.isEditable = false
            outputArea.lineWrap = true
            analyzeButton.addActionListener {
                outputArea.text = "Analyzing...\n"
                try {
                    val extractor = ComponentExtractor(project)
                    val graph = extractor.extract()
                    if (graph.allComponents.isEmpty()) {
                        outputArea.text = "No React components found."
                        return@addActionListener
                    }
                    val sb = StringBuilder()
                    graph.allComponents.values.forEach { comp ->
                        sb.appendLine("📦 ${comp.name}")
                        sb.appendLine("   File: ${comp.filePath}:${comp.lineNumber}")
                        sb.appendLine("   useState: ${comp.hasUseState}")
                        if (comp.props.isNotEmpty()) sb.appendLine("   Props: ${comp.props.joinToString(", ")}")
                        if (comp.children.isNotEmpty()) sb.appendLine("   Children: ${comp.children.joinToString(", ") { it.name }}")
                        sb.appendLine()
                    }
                    outputArea.text = sb.toString()
                } catch (e: Exception) {
                    outputArea.text = "Error: ${e.message}"
                }
            }
            panel.add(analyzeButton, BorderLayout.NORTH)
            panel.add(JScrollPane(outputArea), BorderLayout.CENTER)
            toolWindow.contentManager.addContent(contentFactory.createContent(panel, "", false))
            return
        }

        val browser = JBCefBrowser()

        // JS Query: node click → open file in editor
        val nodeClickQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
        nodeClickQuery.addHandler { nodeName ->
            val target = componentMap[nodeName]
            if (target != null) {
                ApplicationManager.getApplication().invokeLater {
                    val vFile = LocalFileSystem.getInstance().findFileByPath(target.filePath)
                    if (vFile != null) {
                        val descriptor = OpenFileDescriptor(project, vFile, target.lineNumber - 1, 0)
                        descriptor.navigate(true)
                    }
                }
            }
            null
        }

        // Inject JS bridge after page load
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                browser.cefBrowser.executeJavaScript(
                    """
                    window.javaConnector = {
                        onNodeClicked: function(name) {
                            ${nodeClickQuery.inject("name")}
                        }
                    };
                    """.trimIndent(),
                    browser.cefBrowser.url, 0
                )
            }
        }, browser.cefBrowser)

        browser.loadHTML("<html><body style='background:#f0ede8;font-family:sans-serif;padding:20px;color:#999'>Click <b>Analyze React Project</b> to visualize your components.</body></html>")

        analyzeButton.addActionListener {
            try {
                val extractor = ComponentExtractor(project)
                val graph = extractor.extract()

                if (graph.allComponents.isEmpty()) {
                    browser.loadHTML("<html><body style='padding:20px;color:#999;font-family:sans-serif'>No React components found.</body></html>")
                    return@addActionListener
                }

                componentMap = graph.allComponents

                val rootName = graph.root?.name ?: graph.allComponents.keys.first()
                val componentsJson = graph.allComponents.values.joinToString(",") { comp ->
                    val childrenJson = comp.children.joinToString(",") { "\"${it.name}\"" }
                    val propsJson = comp.props.joinToString(",") { "\"$it\"" }
                    """"${comp.name}":{"hasUseState":${comp.hasUseState},"props":[$propsJson],"children":[$childrenJson]}"""
                }
                val json = """{"rootName":"$rootName","components":{$componentsJson}}"""

                browser.loadHTML(buildHtml(json))

            } catch (e: Exception) {
                browser.loadHTML("<html><body style='padding:20px;color:red'>Error: ${e.message}</body></html>")
            }
        }

        panel.add(analyzeButton, BorderLayout.NORTH)
        panel.add(browser.component, BorderLayout.CENTER)
        toolWindow.contentManager.addContent(contentFactory.createContent(panel, "", false))
    }
}