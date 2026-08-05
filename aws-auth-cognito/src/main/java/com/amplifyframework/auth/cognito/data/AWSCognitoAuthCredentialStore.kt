/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package com.amplifyframework.auth.cognito.data

import android.content.Context
import com.amplifyframework.auth.cognito.AuthConfiguration
import com.amplifyframework.core.store.KeyValueRepository
import com.amplifyframework.statemachine.codegen.data.AmplifyCredential
import com.amplifyframework.statemachine.codegen.data.AuthCredentialStore
import com.amplifyframework.statemachine.codegen.data.DeviceMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class AWSCognitoAuthCredentialStore(
    val context: Context,
    private val authConfiguration: AuthConfiguration,
    keyValueRepoFactory: KeyValueRepositoryFactory = KeyValueRepositoryFactory()
) : AuthCredentialStore {

    companion object {
        const val AWS_KEY_VALUE_STORE_IDENTIFIER = "com.amplify.credentialStore"
        private const val KEY_SESSION = "session"
        private const val KEY_DEVICE_METADATA = "deviceMetadata"
        private const val KEY_ASF_DEVICE = "asfDevice"

        // Marks that this (dual-write aware) version of the store has taken ownership of the on-disk
        // data. While unset, an empty default key may mean the install predates the default-key
        // dual-write (a pre-update session lives only under a per-user key) and is recoverable. Once
        // set, an empty default key is a genuine signed-out state and a lingering per-user key must
        // NOT be resurrected — otherwise a sign-out (or a master sign-out while a business is active)
        // would be silently undone.
        private const val KEY_LEGACY_SESSION_MIGRATED = "legacySessionMigrated"

        // Lenient deserialization so a credential persisted by an OLDER app/SDK version still loads
        // after an update instead of being thrown away (which would silently sign the user out).
        // Older versions serialized fields that newer models have since removed (e.g.
        // SignedInData.email); without ignoreUnknownKeys, those stale keys make strict parsing throw
        // and the session is lost on upgrade. Backward compatibility for existing/enterprise users.
        private val json = Json { ignoreUnknownKeys = true }
    }

    private var keyValue: KeyValueRepository =
        keyValueRepoFactory.create(context, AWS_KEY_VALUE_STORE_IDENTIFIER)

    //region Save Credentials

    /**
     * Saves [credential] to the default session key (single-user / upstream-compat). When the
     * credential carries a userId via its [SignedInData] (i.e. [AmplifyCredential.UserPoolTypeCredential]),
     * the same payload is *additionally* written to a userId-prefixed key so per-user reads via
     * [retrieveCredential] (with userId) can locate it. Dual-write keeps single-user callers
     * (no-arg [retrieveCredential]) working unchanged while enabling multi-user routing.
     */
    override fun saveCredential(credential: AmplifyCredential) {
        // Persisting through this version takes ownership of the store: from now on an empty default
        // key is a real signed-out state, not a pre-update layout to recover from.
        markStoreMigrated()
        val serialized = serializeCredential(credential)
        // Always write to the default key so single-user reads continue to work.
        keyValue.put(generateKey(KEY_SESSION), serialized)
        // Additionally write to a per-user key when the credential identifies a user.
        val userId = (credential as? AmplifyCredential.UserPoolTypeCredential)?.signedInData?.userId
        if (!userId.isNullOrEmpty()) {
            keyValue.put(generateUserScopedKey(userId, KEY_SESSION), serialized)
        }
    }

    override fun saveDeviceMetadata(username: String, deviceMetadata: DeviceMetadata) = keyValue.put(
        generateKey("$username.$KEY_DEVICE_METADATA"),
        serializeMetaData(deviceMetadata)
    )

    override fun saveASFDevice(device: AmplifyCredential.ASFDevice) = keyValue.put(
        generateKey(KEY_ASF_DEVICE),
        serializeASFDevice(device)
    )
    //endregion

    //region Retrieve Credentials

    /**
     * Returns the credential for [userId]. When [userId] is null/empty (single-user / upstream
     * path) reads the default session key. When [userId] is non-empty, prefers the userId-prefixed
     * key and falls back to the default key when the per-user entry is missing — which preserves
     * the upgrade path for installations created before multi-user.
     */
    override fun retrieveCredential(userId: String?): AmplifyCredential {
        if (userId.isNullOrEmpty()) {
            val default = deserializeCredential(keyValue.get(generateKey(KEY_SESSION)))
            // Backward compatibility: installs upgraded from a pre-dual-write version (<= 2.26.x-harri)
            // persisted the signed-in session ONLY under a userId-prefixed key and never the default
            // key, so this no-arg boot restore finds nothing and the user is signed out across the
            // update. Recover the existing per-user session so the global session is re-established.
            return if (default !is AmplifyCredential.Empty) default else recoverUserScopedSession()
        }
        val perUser = deserializeCredential(keyValue.get(generateUserScopedKey(userId, KEY_SESSION)))
        return if (perUser !is AmplifyCredential.Empty) {
            perUser
        } else {
            deserializeCredential(keyValue.get(generateKey(KEY_SESSION)))
        }
    }

    override fun retrieveDeviceMetadata(username: String): DeviceMetadata = deserializeMetadata(
        keyValue.get(generateKey("$username.$KEY_DEVICE_METADATA"))
    )

    override fun retrieveASFDevice(): AmplifyCredential.ASFDevice = deserializeASFDevice(
        keyValue.get(generateKey(KEY_ASF_DEVICE))
    )
    //endregion

    //region Delete Credentials

    /**
     * Deletes the credential for [userId]. When [userId] is null/empty (single-user) removes the
     * default session key. When [userId] is non-empty, removes only the userId-prefixed key — the
     * default key is left intact since it is shared across users and the next [saveCredential]
     * will overwrite it.
     */
    override fun deleteCredential(userId: String?) {
        if (userId.isNullOrEmpty()) {
            keyValue.remove(generateKey(KEY_SESSION))
        } else {
            keyValue.remove(generateUserScopedKey(userId, KEY_SESSION))
        }
    }

    override fun deleteDeviceKeyCredential(username: String) = keyValue.remove(
        generateKey("$username.$KEY_DEVICE_METADATA")
    )

    override fun deleteASFDevice() = keyValue.remove(generateKey(KEY_ASF_DEVICE))
    //endregion

    private fun generateKey(keySuffix: String): String {
        var prefix = "amplify"

        authConfiguration.userPool?.let {
            prefix += ".${it.poolId}"
        }
        authConfiguration.identityPool?.let {
            prefix += ".${it.poolId}"
        }

        return prefix.plus(".$keySuffix")
    }

    private fun generateUserScopedKey(userId: String, keySuffix: String): String = "${userId}_${generateKey(keySuffix)}"

    /**
     * Recovers a signed-in session written by a pre-dual-write version of this store
     * (<= 2.26.x-harri), which persisted user sessions ONLY under a userId-prefixed key
     * ("<userId>_<defaultSessionKey>") and never the shared default key. The boot restore
     * ([CredentialStoreCognitoActions] -> retrieveCredential() with no userId) reads the default key,
     * so without this the session would be discarded on update. We read the existing per-user
     * session and promote it to the default key, which re-establishes the global session; the
     * per-user read path then bridges it back to the active user. New installs always populate the
     * default key, so this fallback is never exercised for them.
     */
    private fun recoverUserScopedSession(): AmplifyCredential {
        // Only a never-migrated store can hold a pre-update, default-key-less session. Once this
        // version has owned the store, an empty default key is a genuine sign-out — do not resurrect.
        if (isStoreMigrated()) return AmplifyCredential.Empty
        markStoreMigrated()

        val defaultSessionKey = generateKey(KEY_SESSION)
        val userScopedSessionKey = Regex("^.+_${Regex.escape(defaultSessionKey)}$")
        val recovered = keyValue.keys()
            .asSequence()
            .filter { it.matches(userScopedSessionKey) }
            .map { deserializeCredential(keyValue.get(it)) }
            .firstOrNull { it is AmplifyCredential.UserPoolTypeCredential }
            ?: return AmplifyCredential.Empty
        // Promote to the default key so subsequent no-arg reads and the dual-write model stay consistent.
        keyValue.put(defaultSessionKey, serializeCredential(recovered))
        return recovered
    }

    private fun isStoreMigrated(): Boolean = keyValue.get(generateKey(KEY_LEGACY_SESSION_MIGRATED)) != null

    private fun markStoreMigrated() {
        if (!isStoreMigrated()) keyValue.put(generateKey(KEY_LEGACY_SESSION_MIGRATED), "true")
    }

    //region Deserialization
    private fun deserializeCredential(encodedCredential: String?): AmplifyCredential = try {
        val credentials = encodedCredential?.let { json.decodeFromString(it) as AmplifyCredential }
        credentials ?: AmplifyCredential.Empty
    } catch (e: Exception) {
        AmplifyCredential.Empty
    }

    private fun deserializeMetadata(encodedDeviceMetadata: String?): DeviceMetadata = try {
        val deviceMetadata = encodedDeviceMetadata?.let { json.decodeFromString(it) as DeviceMetadata }
        deviceMetadata ?: DeviceMetadata.Empty
    } catch (e: Exception) {
        DeviceMetadata.Empty
    }

    private fun deserializeASFDevice(encodedASFDevice: String?): AmplifyCredential.ASFDevice = try {
        val asfDevice = encodedASFDevice?.let { json.decodeFromString(it) as AmplifyCredential.ASFDevice }
        asfDevice ?: AmplifyCredential.ASFDevice(null)
    } catch (e: Exception) {
        AmplifyCredential.ASFDevice(null)
    }
    //endregion

    //region Serialization
    private fun serializeCredential(credential: AmplifyCredential): String = json.encodeToString(credential)

    private fun serializeMetaData(deviceMetadata: DeviceMetadata): String = json.encodeToString(deviceMetadata)

    private fun serializeASFDevice(device: AmplifyCredential.ASFDevice): String = json.encodeToString(device)
    //endregion
}
