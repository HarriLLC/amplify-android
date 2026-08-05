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

package com.amplifyframework.statemachine.codegen.data

import com.amplifyframework.auth.cognito.mockSignedInData
import com.amplifyframework.core.store.KeyValueRepository
import com.amplifyframework.statemachine.codegen.states.AuthState
import com.amplifyframework.statemachine.codegen.states.AuthenticationState
import com.amplifyframework.statemachine.codegen.states.AuthorizationState
import com.amplifyframework.statemachine.codegen.states.SignInState
import com.amplifyframework.statemachine.codegen.states.SignUpState
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class AuthStateRepoTest {

    private val store = InMemoryKeyValueRepository()
    private val repo = AuthStateRepo.createForTest(store)

    @Test
    fun `intermediate state stays in-memory and is not persisted`() {
        val intermediate = AuthState.Configured(
            authNState = AuthenticationState.SigningIn(SignInState.NotStarted()),
            authZState = AuthorizationState.Configured(),
            authSignUpState = SignUpState.NotStarted()
        )

        repo.put("userA", intermediate)

        repo.get("userA") shouldBe intermediate
        repo.allInMemoryKeys() shouldBe listOf("userA")
        // Persisted index is empty — only SessionEstablished states are tracked.
        repo.allUserIds() shouldBe setOf("userA")
        store.snapshot().keys.none { it.startsWith("userA") } shouldBe true
    }

    @Test
    fun `SessionEstablished persists to encrypted store and clears in-memory map`() {
        val established = sessionEstablishedState("userA")

        repo.put("userA", established)

        // In-memory cleared so a second login can stack on top.
        repo.allInMemoryKeys() shouldBe emptyList()
        // Persisted index now lists userA.
        repo.allUserIds() shouldBe setOf("userA")
        // get(userA) deserializes from the encrypted store.
        repo.get("userA").shouldNotBeNull()
    }

    @Test
    fun `SignedOut removes the entry from both stores and the persisted index`() {
        repo.put("userA", sessionEstablishedState("userA"))
        repo.allUserIds() shouldBe setOf("userA")

        val signedOut = AuthState.Configured(
            authNState = AuthenticationState.SignedOut(SignedOutData()),
            authZState = AuthorizationState.Configured(),
            authSignUpState = SignUpState.NotStarted()
        )

        repo.put("userA", signedOut)

        repo.get("userA").shouldBeNull()
        repo.allUserIds() shouldBe emptySet()
    }

    @Test
    fun `allUserIds unions in-memory keys with persisted index`() {
        // userA persisted (SessionEstablished)
        repo.put("userA", sessionEstablishedState("userA"))
        // userB intermediate (in-memory)
        repo.put(
            "userB",
            AuthState.Configured(
                authNState = AuthenticationState.SigningIn(SignInState.NotStarted()),
                authZState = AuthorizationState.Configured(),
                authSignUpState = SignUpState.NotStarted()
            )
        )

        repo.allUserIds() shouldContainExactlyInAnyOrder setOf("userA", "userB")
    }

    @Test
    fun `activeStateKey returns the most recently pushed in-memory user`() {
        val intermediate = AuthState.Configured(
            authNState = AuthenticationState.SigningIn(SignInState.NotStarted()),
            authZState = AuthorizationState.Configured(),
            authSignUpState = SignUpState.NotStarted()
        )
        repo.put("userA", intermediate)
        repo.put("userB", intermediate)

        repo.activeStateKey() shouldBe "userB"
    }

    @Test
    fun `persisted index survives in-memory clear and is recoverable from a fresh repo instance`() {
        // Save through the first repo instance, sharing the store.
        repo.put("userA", sessionEstablishedState("userA"))
        repo.put("userB", sessionEstablishedState("userB"))

        // Spin up a fresh repo over the same backing store (simulates process death + restart).
        val freshRepo = AuthStateRepo.createForTest(store)

        freshRepo.allUserIds() shouldContainExactlyInAnyOrder setOf("userA", "userB")
        freshRepo.get("userA").shouldNotBeNull()
        freshRepo.get("userB").shouldNotBeNull()
    }

    @Test
    fun `clearInMemory drops in-memory entries but leaves persisted index`() {
        repo.put("userA", sessionEstablishedState("userA"))
        val intermediate = AuthState.Configured(
            authNState = AuthenticationState.SigningIn(SignInState.NotStarted()),
            authZState = AuthorizationState.Configured(),
            authSignUpState = SignUpState.NotStarted()
        )
        repo.put("userB", intermediate)

        repo.clearInMemory()

        repo.allInMemoryKeys() shouldBe emptyList()
        // userA still persisted; userB is gone (was only in-memory).
        repo.allUserIds() shouldBe setOf("userA")
    }

    @Test
    fun `getDefaultConfiguredState returns a fresh signed-out configured state`() {
        val defaultState = repo.getDefaultConfiguredState()

        defaultState.shouldBeInstanceOf<AuthState.Configured>()
        defaultState.authNState.shouldBeInstanceOf<AuthenticationState.SignedOut>()
        defaultState.authZState.shouldBeInstanceOf<AuthorizationState.Configured>()
    }

    @Test
    fun `migrateFromLegacyStore reshapes legacy sessions so they restore as established`() {
        // Two users persisted by the pre-refactor store in the old {authNState, authZState} envelope.
        val legacyStore = InMemoryKeyValueRepository()
        legacyStore.put("userA", legacyEnvelope("userA"))
        legacyStore.put("userB", legacyEnvelope("userB"))

        repo.migrateFromLegacyStore(legacyStore)

        // Both restore as fully-established sessions in the current store.
        repo.get("userA").shouldNotBeNull().isSessionEstablished shouldBe true
        repo.get("userB").shouldNotBeNull().isSessionEstablished shouldBe true
        repo.allUserIds() shouldContainExactlyInAnyOrder listOf("userA", "userB")
    }

    @Test
    fun `migrateFromLegacyStore runs at most once`() {
        val legacyStore = InMemoryKeyValueRepository()
        legacyStore.put("userA", legacyEnvelope("userA"))
        repo.migrateFromLegacyStore(legacyStore)

        // A user signs out after migration; a second migration must NOT resurrect them.
        repo.remove("userA")
        repo.migrateFromLegacyStore(legacyStore)

        repo.get("userA").shouldBeNull()
    }

    /**
     * Builds a session in the pre-refactor `{authNState:{signedInData,deviceMetadata}, authZState:{amplifyCredential}}`
     * shape by taking the inner JSON the current store writes and re-nesting it, so the fixture uses
     * the exact serialization the repo expects on read-back.
     */
    private fun legacyEnvelope(userId: String): String {
        val tmpStore = InMemoryKeyValueRepository()
        AuthStateRepo.createForTest(tmpStore).put(userId, sessionEstablishedState(userId))
        val current = Json.parseToJsonElement(tmpStore.get(userId)!!).jsonObject
        return buildJsonObject {
            put(
                "authNState",
                buildJsonObject {
                    put("signedInData", current.getValue("signedInData"))
                    put("deviceMetadata", current.getValue("deviceMetadata"))
                }
            )
            put(
                "authZState",
                buildJsonObject {
                    put("amplifyCredential", current.getValue("amplifyCredential"))
                }
            )
        }.toString()
    }

    private fun sessionEstablishedState(userId: String): AuthState = AuthState.Configured(
        authNState = AuthenticationState.SignedIn(
            mockSignedInData(userId = userId, username = userId),
            DeviceMetadata.Empty
        ),
        authZState = AuthorizationState.SessionEstablished(AmplifyCredential.Empty),
        authSignUpState = null
    )
}

/**
 * Minimal in-memory [KeyValueRepository] used by [AuthStateRepoTest]. Substitutes the
 * encrypted store so tests can drive persistence without keystore access.
 */
private class InMemoryKeyValueRepository : KeyValueRepository {
    private val map = mutableMapOf<String, String>()
    override fun put(dataKey: String, value: String?) {
        if (value == null) map.remove(dataKey) else map[dataKey] = value
    }
    override fun get(dataKey: String): String? = map[dataKey]
    override fun remove(dataKey: String) {
        map.remove(dataKey)
    }
    override fun removeAll() {
        map.clear()
    }
    override fun keys(): Set<String> = map.keys.toSet()
    fun snapshot(): Map<String, String> = map.toMap()
}
