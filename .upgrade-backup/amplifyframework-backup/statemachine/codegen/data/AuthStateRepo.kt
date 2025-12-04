package com.amplifyframework.statemachine.codegen.data

import android.content.Context
import com.amplifyframework.core.store.EncryptedKeyValueRepository
import com.amplifyframework.statemachine.codegen.states.AuthState
import com.amplifyframework.statemachine.codegen.states.AuthenticationState
import com.amplifyframework.statemachine.codegen.states.AuthorizationState
import com.amplifyframework.statemachine.codegen.states.SignUpState
import com.amplifyframework.statemachine.util.LifoMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repository for managing authentication states.
 * This class uses an in-memory LIFO map and an encrypted key-value store to persist authentication states.
 *
 * @constructor Creates an instance of AuthStateRepo with the provided context.
 * @param context The context used to initialize the encrypted key-value store.
 */
internal class AuthStateRepo private constructor(context: Context) {

    // In-memory LIFO map to store authentication states.
    private val authStateMap = LifoMap.empty<String, AuthState>()

    // Encrypted key-value store for persisting authentication states.
    private val encryptedStore = EncryptedKeyValueRepository(
        context,
        PREF_KEY
    )

    /**
     * Stores the given authentication state associated with the specified key.
     *
     * @param key The key to associate with the authentication state.
     * @param value The authentication state to store.
     */
    fun put(key: String, value: AuthState) {
        if (value.isSignedOut) {
            remove(key)
            return
        }
        if (value.isSessionEstablished) {
            encryptedStore.put(
                key,
                serializeAuthNAndZState(
                    AuthNAndAuthZ(
                        value.authNState as AuthenticationState.SignedIn,
                        value.authZState as AuthorizationState.SessionEstablished
                    )
                )
            )
            // Remove all states from the in-memory map.
            // This enables us to login again with different credentials.
            authStateMap.clear()
            return
        }
        authStateMap.push(key, value)
    }

    /**
     * Retrieves the authentication state associated with the specified key.
     *
     * @param key The key associated with the authentication state.
     * @return The authentication state if found, or null otherwise.
     */
    fun get(key: String): AuthState? {
        return if (authStateMap.containsKey(key)) {
            authStateMap.get(key)
        } else {
            deserializeAuthNAndZState(encryptedStore.get(key))?.let {
                AuthState.Configured(it.authNState, it.authZState, null)
            }
        }
    }

    /**
     * Removes the authentication state associated with the specified key.
     *
     * @param key The key associated with the authentication state to remove.
     */
    fun remove(key: String) {
        authStateMap.pop(key)
        encryptedStore.remove(key)
    }

    /**
     * Retrieves the most recently added authentication state.
     *
     * @return The most recently added authentication state, or null if none exists.
     */
    fun activeState(): AuthState? {
        return authStateMap.peek()
    }

    /**
     * Retrieves the key associated with the most recently added authentication state.
     *
     * @return The key associated with the most recently added authentication state, or null if none exists.
     */
    fun activeStateKey(): String? {
        return authStateMap.peekKey()
    }

    fun getDefaultConfiguredState(): AuthState {
        return AuthState.Configured(
            authNState = AuthenticationState.SignedOut(SignedOutData()),
            authZState = AuthorizationState.Configured(),
            authSignUpState = SignUpState.NotStarted()
        )
    }

    private fun serializeAuthNAndZState(authState: AuthNAndAuthZ): String {
        return Json.encodeToString(authState)
    }

    private fun deserializeAuthNAndZState(encodedState: String?): AuthNAndAuthZ? {
        return runCatching {
            encodedState?.let { Json.decodeFromString(it) as AuthNAndAuthZ }
        }.getOrNull()
    }

    companion object {

        // Preference key for the encrypted key-value store.
        private val PREF_KEY = Companion::class.java.name

        private var instance: AuthStateRepo? = null

        /**
         * Retrieves the singleton instance of AuthStateRepo.
         *
         * @param context The context used to initialize the encrypted key-value store.
         * @return The singleton instance of AuthStateRepo.
         */
        @Synchronized
        fun getInstance(context: Context): AuthStateRepo {
            if (instance == null) {
                instance = AuthStateRepo(context)
            }
            return instance!!
        }
    }
}

@Serializable
private data class AuthNAndAuthZ(
    val authNState: AuthenticationState.SignedIn,
    val authZState: AuthorizationState.SessionEstablished
)

internal val AuthState.isSessionEstablished: Boolean
    get() = this is AuthState.Configured &&
            this.authNState is AuthenticationState.SignedIn &&
            this.authZState is AuthorizationState.SessionEstablished

private val AuthState.isSignedOut: Boolean
    get() = this is AuthState.Configured &&
            this.authNState is AuthenticationState.SignedOut