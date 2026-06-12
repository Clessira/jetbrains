package com.clessira.nowdoing.app

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Bootstraps the application service when the first project opens. */
class NowDoingStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        NowDoingAppService.instance.ensureStarted()
    }
}
