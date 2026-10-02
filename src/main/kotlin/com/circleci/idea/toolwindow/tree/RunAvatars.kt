package com.circleci.idea.toolwindow.tree

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.state.Run
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Avatars of the people who triggered runs. Loaded in the background and
 * cached, so a row shows nothing in the avatar's place until it arrives.
 *
 * Only GitHub serves an avatar for a login at a predictable URL, so runs of
 * other providers' projects have none.
 */
@Service(Service.Level.APP)
class RunAvatars(private val scope: CoroutineScope) {
    // The most recently used avatars, by URL; one that failed to load stays null.
    private val avatars =
        object : LinkedHashMap<String, MutableStateFlow<ImageBitmap?>>(MAX_CACHED, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableStateFlow<ImageBitmap?>>) =
                size > MAX_CACHED
        }

    /** The avatar at [url]: null until it loads, and if it doesn't. */
    fun avatar(url: String): StateFlow<ImageBitmap?> =
        synchronized(avatars) {
            avatars.getOrPut(url) { MutableStateFlow<ImageBitmap?>(null).also { load(url, it) } }.asStateFlow()
        }

    private fun load(
        url: String,
        into: MutableStateFlow<ImageBitmap?>,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                into.value = HttpRequests.request(url).connect { ImageIO.read(it.inputStream) }?.toComposeImageBitmap()
            } catch (e: IOException) {
                CircleCILogger.getInstance().debug("Couldn't load avatar $url: ${e.message}")
            }
        }
    }

    companion object {
        private const val MAX_CACHED = 200
        private const val LOAD_FACTOR = 0.75f

        /** How big an avatar is drawn, ring and all: as tall as a run's two lines of text. */
        const val SIZE = 24

        // Twice the drawn size, for HiDPI screens.
        private const val REQUEST_SIZE = SIZE * 2

        fun getInstance(): RunAvatars = ApplicationManager.getApplication().getService(RunAvatars::class.java)

        /** GitHub's avatar for whoever triggered the run, for a GitHub project. */
        internal fun avatarUrl(run: Run): String? {
            if (run.projectSlug?.startsWith("gh/") != true) return null
            val login = run.triggeredBy?.takeIf { it.isNotBlank() } ?: return null
            return "https://github.com/$login.png?size=$REQUEST_SIZE"
        }
    }
}
