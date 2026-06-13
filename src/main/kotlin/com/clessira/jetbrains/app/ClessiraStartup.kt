package com.clessira.jetbrains.app

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Bootstraps the application service when the first project opens. */
class ClessiraStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ClessiraAppService.instance.ensureStarted()
    }
}
