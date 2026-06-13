package com.clessira.jetbrains.git

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import git4idea.repo.GitRepositoryManager

/**
 * Seeds the initial branch of every open repository at project startup so the
 * first real switch reports the correct `previousBranch` — without announcing
 * the branch that was already checked out (VS Code parity).
 */
internal class RepoWatchStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = project.service<RepoWatchService>()
        GitRepositoryManager.getInstance(project).repositories.forEach {
            service.repositoryChanged(it)
        }
    }
}
