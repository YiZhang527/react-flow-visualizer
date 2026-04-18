package com.github.reactflow

import com.github.reactflow.analysis.ComponentExtractor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import javax.swing.*

class MyToolWindowFactory : ToolWindowFactory, DumbAware {

    private fun buildHtml(json: String): String {
        val graphHtml = javaClass.classLoader.getResource("graph.html")?.readText() ?: return "<p>graph.html not found</p>"
        // Inject the real data by replacing the demo initGraph call
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
            // Fallback text mode
            val outputArea = JTextArea("Click 'Analyze' to scan your React project.\n")
            outputArea.isEditable = false
            outputArea.lineWrap = true
            analyzeButton.addActionListener {
                outputArea.text = "Analyzing...\n"
                try {
                    val extractor = ComponentExtractor(project)
                    val graph = extractor.extract()
                    if (graph.allComponents.isEmpty()) {
                        outputArea.text = "No React components found.\nMake sure you opened a React project."
                        return@addActionListener
                    }
                    val sb = StringBuilder()
                    sb.appendLine("Found ${graph.allComponents.size} component(s):\n")
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
        browser.loadHTML("<html><body style='background:#f5f5f0;font-family:sans-serif;padding:20px;color:#999'>Click <b>Analyze React Project</b> to visualize your components.</body></html>")

        analyzeButton.addActionListener {
            try {
                val extractor = ComponentExtractor(project)
                val graph = extractor.extract()

                if (graph.allComponents.isEmpty()) {
                    browser.loadHTML("<html><body style='padding:20px;color:#999;font-family:sans-serif'>No React components found.<br>Make sure you opened a React project.</body></html>")
                    return@addActionListener
                }

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