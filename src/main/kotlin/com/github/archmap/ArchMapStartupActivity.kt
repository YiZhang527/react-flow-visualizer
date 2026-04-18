package com.github.archmap

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ArchMapStartupActivity : ProjectActivity {

    private val log = Logger.getInstance(ArchMapStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        log.info("ArchMap: project opened — ${project.name}")
    }
}
