package com.circleci.idea.toolwindow.tree

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.state.Run
import com.intellij.collaboration.ui.codereview.avatar.CodeReviewAvatarUtils
import com.intellij.collaboration.ui.icon.AsyncImageIconsProvider
import com.intellij.collaboration.ui.icon.CachingIconsProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.ui.JBColor
import com.intellij.util.io.HttpRequests
import com.intellij.util.ui.ImageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Image
import java.io.IOException
import javax.imageio.ImageIO
import javax.swing.Icon

/**
 * Avatars of the people who triggered runs, drawn as the Pull Requests list
 * draws reviewers': a circle in a ring. Loaded in the background and cached,
 * so a tree row shows nothing in the avatar's place until it arrives.
 *
 * Only GitHub serves an avatar for a login at a predictable URL, so runs of
 * other providers' projects have none.
 */
@Service(Service.Level.APP)
class RunAvatars(scope: CoroutineScope) {
    private val icons =
        CachingIconsProvider(AsyncImageIconsProvider(scope, AvatarLoader())) {
            maxSize = MAX_CACHED
            expiresAfterMinutes = EXPIRES_AFTER_MINUTES
        }

    /** The run's avatar in a ring of its status's colour, or null when there's no avatar to show. */
    fun iconFor(run: Run): Icon? {
        val url = avatarUrl(run) ?: return null
        return CodeReviewAvatarUtils.createIconWithOutline(icons.getIcon(url, SIZE), RING_COLOR)
    }

    private class AvatarLoader : AsyncImageIconsProvider.AsyncImageLoader<String> {
        override suspend fun load(key: String): Image? =
            withContext(Dispatchers.IO) {
                try {
                    HttpRequests.request(key).connect { ImageIO.read(it.inputStream) }
                } catch (e: IOException) {
                    CircleCILogger.getInstance().debug("Couldn't load avatar $key: ${e.message}")
                    null
                }
            }

        // GitHub serves square avatars; the ring is drawn around a circle, so crop to one.
        override suspend fun postProcess(image: Image): Image =
            ImageUtil.createCircleImage(ImageUtil.toBufferedImage(image))
    }

    companion object {
        // The same neutral ring for every run: the status icon beside it already says how the run went.
        private val RING_COLOR = JBColor.namedColor("Review.Avatar.Border.Status.Empty", JBColor(0xD3D5DB, 0x4E5157))

        private const val MAX_CACHED = 200
        private const val EXPIRES_AFTER_MINUTES = 60

        // With its ring, as tall as a run's two lines of text, so it fills the row without growing it.
        private const val SIZE = 24

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
