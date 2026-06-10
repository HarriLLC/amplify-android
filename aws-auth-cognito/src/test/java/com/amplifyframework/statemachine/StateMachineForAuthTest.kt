/*
 * Copyright 2026 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amplifyframework.statemachine

import com.amplifyframework.auth.cognito.AuthConfiguration
import com.amplifyframework.auth.cognito.AuthEnvironment
import com.amplifyframework.auth.cognito.AuthStateMachine
import com.amplifyframework.auth.cognito.mockSignedInData
import com.amplifyframework.logging.Logger
import com.amplifyframework.statemachine.codegen.data.AmplifyCredential
import com.amplifyframework.statemachine.codegen.data.AuthStateRepo
import com.amplifyframework.statemachine.codegen.data.DeviceMetadata
import com.amplifyframework.statemachine.codegen.data.SignedOutData
import com.amplifyframework.statemachine.codegen.states.AuthState
import com.amplifyframework.statemachine.codegen.states.AuthenticationState
import com.amplifyframework.statemachine.codegen.states.AuthorizationState
import com.amplifyframework.statemachine.codegen.states.SignUpState
import io.mockk.mockk
import java.io.File
import kotlin.test.assertTrue
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests the multi-user-aware per-user read path of [StateMachineForAuth].
 *
 * Focus: the boot/configure restore bridge. The single-user configure restore lands the stored
 * session on the global `_state` (dispatched without a userId, so it stays at `SessionEstablished`),
 * but the per-user [AuthStateRepo] entry may not be populated yet — e.g. the first launch after an
 * app update, in the window between the restore emitting and its per-user persist landing. A per-user
 * `fetchAuthSession(userId)` released by the configure gate in that window must resolve to the
 * restored session (and refresh it), NOT to the signed-out default that triggers the unauthenticated
 * session path (`Identity Pool not configured` on a User-Pool-only config).
 *
 * The mock [AuthEnvironment.context] makes `EncryptedKeyValueRepository` construction fail, so
 * [AuthStateRepo] degrades to its empty in-memory fallback — i.e. every per-user read misses the
 * repo, exercising the bridge directly.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class StateMachineForAuthTest {

    private val logger = mockk<Logger>(relaxed = true)
    private val configuration = loadConfiguration()

    private val environment = AuthEnvironment(
        context = mockk(),
        configuration = configuration,
        cognitoAuthService = mockk(relaxed = true),
        credentialStoreClient = mockk(relaxed = true),
        userContextDataProvider = null,
        hostedUIClient = mockk(relaxed = true),
        logger = logger
    )

    private val mainThreadSurrogate = newSingleThreadContext("Main thread")

    @Before
    fun setup() {
        Dispatchers.setMain(mainThreadSurrogate)
        // Drop any AuthStateRepo singleton leaked by another test so this test starts with an empty repo.
        AuthStateRepo.resetInstanceForTest()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        AuthStateRepo.resetInstanceForTest()
    }

    @Test
    fun `per-user read falls back to the restored global session when the repo misses and userId matches`() {
        val machine = AuthStateMachine(environment, initialState = sessionEstablished("userA"))

        val state = runBlocking { machine.getStateForUser("userA") }

        assertTrue(state.authNState is AuthenticationState.SignedIn)
        assertTrue(state.authZState is AuthorizationState.SessionEstablished)
    }

    @Test
    fun `per-user read returns the signed-out default when the restored global session is for another user`() {
        val machine = AuthStateMachine(environment, initialState = sessionEstablished("userA"))

        val state = runBlocking { machine.getStateForUser("userB") }

        // No cross-user leak: userB has no session, so it must not receive userA's restored session.
        assertTrue(state.authNState is AuthenticationState.SignedOut)
        assertTrue(state.authZState is AuthorizationState.Configured)
    }

    @Test
    fun `per-user read returns the signed-out default when the global state is not an established session`() {
        val signedOut = AuthState.Configured(
            authNState = AuthenticationState.SignedOut(SignedOutData()),
            authZState = AuthorizationState.Configured(),
            authSignUpState = SignUpState.NotStarted()
        )
        val machine = AuthStateMachine(environment, initialState = signedOut)

        val state = runBlocking { machine.getStateForUser("userA") }

        assertTrue(state.authNState is AuthenticationState.SignedOut)
        assertTrue(state.authZState is AuthorizationState.Configured)
    }

    private fun sessionEstablished(userId: String): AuthState = AuthState.Configured(
        authNState = AuthenticationState.SignedIn(
            mockSignedInData(userId = userId, username = userId),
            DeviceMetadata.Empty
        ),
        authZState = AuthorizationState.SessionEstablished(AmplifyCredential.Empty),
        authSignUpState = null
    )

    private fun loadConfiguration(): AuthConfiguration {
        val configFileUrl = this::class.java.getResource(CONFIGURATION_PATH)
        val configJSONObject = JSONObject(File(configFileUrl!!.file).readText())
            .getJSONObject("auth")
            .getJSONObject("plugins")
            .getJSONObject("awsCognitoAuthPlugin")
        return AuthConfiguration.fromJson(configJSONObject)
    }

    companion object {
        private const val CONFIGURATION_PATH = "/feature-test/configuration/authconfiguration_oauth.json"
    }
}
