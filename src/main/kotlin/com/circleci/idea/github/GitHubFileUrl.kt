package com.circleci.idea.github

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A file in a GitHub repository at a ref, as a raw file URL names it. */
internal data class GitHubFileUrl(val owner: String, val repo: String, val ref: String, val path: String) {
    companion object {
        /**
         * The file a raw.githubusercontent.com URL (`/owner/repo/ref/path`) or a github.com
         * raw URL (`/owner/repo/raw/ref/path`) names, or null for any other URL.
         *
         * A ref can be spelled `refs/heads/<branch>` or `refs/tags/<tag>`. Otherwise it's
         * taken to be one segment: a branch with a slash in it reads as a shorter branch
         * and a longer path, as GitHub can't tell them apart from the URL either.
         */
        fun parse(url: String): GitHubFileUrl? {
            val uri = runCatching { URI(url) }.getOrNull()?.takeIf { it.scheme == "https" } ?: return null
            val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map(::decode)
            val rest =
                when (uri.host) {
                    "raw.githubusercontent.com" -> segments
                    "github.com" -> segments.takeIf { it.getOrNull(2) == "raw" }?.let { it.take(2) + it.drop(3) }
                    else -> null
                }
            return rest?.takeIf { it.size >= 4 }?.let(::fromSegments)
        }

        // owner, repo, then the ref and the path.
        private fun fromSegments(segments: List<String>): GitHubFileUrl? {
            val refLength = if (segments[2] == "refs" && segments.getOrNull(3) in setOf("heads", "tags")) 3 else 1
            val path = segments.drop(2 + refLength).joinToString("/")
            val ref = segments.subList(2, minOf(2 + refLength, segments.size)).joinToString("/")
            return if (path.isEmpty()) null else GitHubFileUrl(segments[0], segments[1], ref, path)
        }

        private fun decode(segment: String) = URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8)
    }

    /** The REST API URL of the file's contents, under [apiUrl] (such as `https://api.github.com`). */
    fun contentsUrl(apiUrl: String): String {
        val encodedPath = path.split('/').joinToString("/", transform = ::encode)
        return "${apiUrl.trimEnd('/')}/repos/${encode(owner)}/${encode(repo)}/contents/$encodedPath?ref=${encode(ref)}"
    }

    private fun encode(part: String) = URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20")
}
