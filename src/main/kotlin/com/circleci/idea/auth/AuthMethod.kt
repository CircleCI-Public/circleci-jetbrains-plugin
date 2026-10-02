package com.circleci.idea.auth

/** How a CircleCI token was got. */
enum class AuthMethod(val id: String, val description: String) {
    /** In the browser, through CircleCI's OAuth login. */
    OAUTH("oauth", "in the browser"),

    /** A personal API token, pasted in. */
    TOKEN("token", "with a token"),
    ;

    companion object {
        /** The method [id] names; a token stored before methods were recorded was pasted. */
        fun from(id: String): AuthMethod = entries.firstOrNull { it.id == id } ?: TOKEN
    }
}
