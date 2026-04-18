package com.github.archmap

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ArchMapToolWindowFactory : ToolWindowFactory, DumbAware {

    private val log = Logger.getInstance(ArchMapToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ArchMapPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
        ArchMapPanelHolder.setPanel(project, panel)

        ApplicationManager.getApplication().executeOnPooledThread {
            val json = try {
                RegexArchGraph.buildJson(project)
            } catch (e: Exception) {
                log.error("ArchMap regex graph failed", e)
                """{"nodes":[],"edges":[]}"""
            }
            log.info("ArchMap: graph JSON length ${json.length}")
            ApplicationManager.getApplication().invokeLater {
                panel.showGraphView(json)
            }
        }
    }
}

object ArchMapPanelHolder {
    private val panels = mutableMapOf<String, ArchMapPanel>()
    fun setPanel(project: Project, panel: ArchMapPanel) {
        project.basePath?.let { panels[it] = panel }
    }

    fun getPanel(project: Project): ArchMapPanel? = project.basePath?.let { panels[it] }
    fun removePanel(project: Project) {
        project.basePath?.let { panels.remove(it) }
    }
}
