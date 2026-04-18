package com.github.reactflow

import com.github.reactflow.analysis.ComponentExtractor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.*

class MyToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        val panel = JPanel(BorderLayout())
        val outputArea = JTextArea("Click 'Analyze' to scan your React project.\n")
        outputArea.isEditable = false
        outputArea.lineWrap = true

        val analyzeButton = JButton("Analyze React Project")
        analyzeButton.addActionListener {
            outputArea.text = "Analyzing...\n"
            try {
                val extractor = ComponentExtractor(project)
                val graph = extractor.extract()

                if (graph.allComponents.isEmpty()) {
                    outputArea.text = "No React components found.\nMake sure you opened a React project."
                } else {
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
                }
            } catch (e: Exception) {
                outputArea.text = "Error: ${e.message}"
            }
        }

        panel.add(analyzeButton, BorderLayout.NORTH)
        panel.add(JScrollPane(outputArea), BorderLayout.CENTER)

        val content = contentFactory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
