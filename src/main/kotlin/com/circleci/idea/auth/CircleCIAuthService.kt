package com.circleci.idea.auth

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.settings.CircleCISettings
import com.circleci.idea.state.AuthState
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.User
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

/**
 * Service for managing CircleCI authentication.
 * Handles token storage, validation, and user authentication state.
 */
@Service(Service.Level.PROJECT)
class CircleCIAuthService(private val project: Project) {

    private val log = logger<CircleCIAuthService>()
    private val passwordSafe = PasswordSafe.instance
    private val apiService = CircleCIApiService.getInstance()

    companion object {
        private const val CREDENTIAL_SERVICE_NAME = "CircleCI"
        private const val CREDENTIAL_USER_NAME = "api-token"

        fun getInstance(project: Project): CircleCIAuthService {
            return project.service()
        }
    }

    /**
     * Get the current authentication token.
     */
    fun getToken(): String? {
        val credentials = passwordSafe.get(createCredentialAttributes())
        return credentials?.getPasswordAsString()
    }

    /**
     * Check if user is authenticated.
     */
    fun isAuthenticated(): Boolean {
        val token = getToken()
        return token != null && token.isNotEmpty()
    }

    /**
     * Authenticate with a token.
     * Validates the token and stores it securely if valid.
     */
    fun login(token: String, hostUrl: String? = null): Result<User> {
        val settings = CircleCISettings.getInstance()
        val effectiveHostUrl = hostUrl ?: settings.hostUrl

        // Validate token by calling the API
        apiService.initialize(token, effectiveHostUrl)

        return apiService.getCurrentUser().fold(
            onSuccess = { userInfo ->
                // Token is valid, store it securely
                storeToken(token)

                // Update settings if host URL changed
                if (hostUrl != null && hostUrl != settings.hostUrl) {
                    settings.hostUrl = hostUrl
                }

                // Create user object
                val user = User(
                    id = userInfo.id,
                    login = userInfo.login,
                    name = userInfo.name ?: userInfo.login
                )

                // Update state
                updateAuthState { state ->
                    state.copy(
                        isAuthenticated = true,
                        token = redactToken(token),
                        hostUrl = effectiveHostUrl,
                        user = user,
                        error = null
                    )
                }

                log.info("Successfully authenticated as ${user.login}")
                Result.success(user)
            },
            onFailure = { error ->
                // Token is invalid
                val errorMessage = error.message ?: "Authentication failed"
                log.warn("Authentication failed: $errorMessage")

                updateAuthState { state ->
                    state.copy(
                        isAuthenticated = false,
                        token = null,
                        user = null,
                        error = errorMessage
                    )
                }

                Result.failure(error)
            }
        )
    }

    /**
     * Logout and clear stored token.
     */
    fun logout() {
        // Clear token from secure storage
        passwordSafe.set(createCredentialAttributes(), null)

        // Update state
        updateAuthState {
            AuthState(hostUrl = CircleCISettings.getInstance().hostUrl)
        }

        // Clear all project data
        project.service<CircleCIStateStore>().clearAllData()

        log.info("User logged out")
    }

    /**
     * Validate the current token.
     * Should be called periodically or on API 401 errors.
     */
    fun validateToken(): Result<User> {
        val token = getToken()
        if (token == null || token.isEmpty()) {
            return Result.failure(IllegalStateException("No token stored"))
        }

        val settings = CircleCISettings.getInstance()
        apiService.initialize(token, settings.hostUrl)

        return apiService.getCurrentUser().fold(
            onSuccess = { userInfo ->
                val user = User(
                    id = userInfo.id,
                    login = userInfo.login,
                    name = userInfo.name ?: userInfo.login
                )

                updateAuthState { state ->
                    state.copy(
                        isAuthenticated = true,
                        user = user,
                        error = null
                    )
                }

                Result.success(user)
            },
            onFailure = { error ->
                log.warn("Token validation failed: ${error.message}")

                updateAuthState { state ->
                    state.copy(
                        isAuthenticated = false,
                        error = error.message
                    )
                }

                Result.failure(error)
            }
        )
    }

    /**
     * Handle authentication error (typically 401).
     * Marks user as unauthenticated and prompts for re-login.
     */
    fun handleAuthenticationError(error: String) {
        log.warn("Authentication error: $error")

        updateAuthState { state ->
            state.copy(
                isAuthenticated = false,
                error = error
            )
        }
    }

    /**
     * Store token securely using PasswordSafe.
     */
    private fun storeToken(token: String) {
        val credentials = Credentials(CREDENTIAL_USER_NAME, token)
        passwordSafe.set(createCredentialAttributes(), credentials)
    }

    /**
     * Create credential attributes for PasswordSafe.
     */
    private fun createCredentialAttributes(): CredentialAttributes {
        return CredentialAttributes(
            serviceName = CREDENTIAL_SERVICE_NAME,
            userName = CREDENTIAL_USER_NAME
        )
    }

    /**
     * Update auth state in the state store.
     */
    private fun updateAuthState(update: (AuthState) -> AuthState) {
        project.service<CircleCIStateStore>().updateAuth(update)
    }

    /**
     * Redact token for display (show only last 4 characters).
     */
    private fun redactToken(token: String): String {
        return if (token.length > 4) {
            "***${token.takeLast(4)}"
        } else {
            "***"
        }
    }

    /**
     * Try to restore authentication on service initialization.
     * Should be called when the project is opened.
     */
    fun restoreAuthentication() {
        val token = getToken()
        if (token != null && token.isNotEmpty()) {
            log.info("Found stored token, validating...")
            validateToken()
        }
    }
}
