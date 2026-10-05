package com.circleci.idea.api

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts.ProgressTitle
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [change] to something in CircleCI, such as creating or deleting it, under
 * the IDE's progress indicator titled [title]. It can't be cancelled: a
 * request already sent may still take effect.
 */
suspend fun <T> withChangeProgress(
    project: Project,
    title: @ProgressTitle String,
    change: suspend () -> T,
): T = withBackgroundProgress(project, title, cancellable = false) { change() }

/**
 * Makes changes for actions, in the project's scope, so that closing what
 * started one doesn't cancel it.
 */
@Service(Service.Level.PROJECT)
class ChangeService(private val project: Project, private val scope: CoroutineScope) {
    /** [change], made on IO with [withChangeProgress]; [then] gets its result on the EDT. */
    fun <T> launch(
        title: @ProgressTitle String,
        change: () -> Result<T>,
        then: (Result<T>) -> Unit,
    ) {
        scope.launch {
            val result = withChangeProgress(project, title) { withContext(Dispatchers.IO) { change() } }
            withContext(Dispatchers.EDT) { then(result) }
        }
    }

    companion object {
        fun getInstance(project: Project): ChangeService = project.getService(ChangeService::class.java)
    }
}
