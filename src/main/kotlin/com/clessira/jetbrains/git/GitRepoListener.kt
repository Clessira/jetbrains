package com.clessira.jetbrains.git

import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager

/** Forwards git4idea repository state changes to [RepoWatchService]. */
internal class GitRepoListener(private val project: Project) : GitRepositoryChangeListener {
    override fun repositoryChanged(repository: GitRepository) {
        project.service<RepoWatchService>().repositoryChanged(repository)
    }
}

/** Seeds newly mapped repositories and prunes removed ones. */
internal class GitMappingListener(private val project: Project) : VcsRepositoryMappingListener {
    override fun mappingChanged() {
        val service = project.service<RepoWatchService>()
        val repositories = GitRepositoryManager.getInstance(project).repositories
        repositories.forEach { service.repositoryChanged(it) }
        service.retainRepositories(repositories.map { it.root.path })
    }
}
