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
package com.amplifyframework.auth.cognito

/**
 * Harri fork: records the outcome of the most recent hosted-UI (Custom Tab) round trip so the
 * host app can attach real diagnostics to sign-in failures.
 *
 * The stock plugin collapses every non-redirect return into a bare `UserCancelledException`,
 * which makes a user abandoning the IdP page after minutes indistinguishable from the tab
 * bouncing in milliseconds (no browser, process death, broken redirect). Production triage
 * needs that distinction, and only this layer can observe it.
 *
 * Events are process-local and overwritten by each hosted-UI transition; consumers should read
 * (or [consumeLastEvent]) immediately when a sign-in failure surfaces.
 */
object HostedUiDiagnostics {

    /** What the hosted-UI layer last observed. */
    enum class EventType {
        /** The Custom Tab intent was started. */
        LAUNCHED,

        /** The redirect URI came back — the OAuth round trip completed. */
        COMPLETED,

        /** Returned without a response URI; surfaced to the caller as user-cancelled. */
        CANCELLED,

        /** `startActivity` failed — no activity could handle the Custom Tab intent. */
        LAUNCH_FAILED,

        /** Recreated after process death with the tab intent lost; flow cannot continue. */
        INTENT_LOST,

        /** A stale global SignedIn state was reset to allow this hosted-UI sign-in to proceed. */
        SIGNED_IN_STATE_RECOVERED
    }

    /**
     * One hosted-UI observation.
     *
     * [tabElapsedMs] is the wall time between launching the tab and returning to the app
     * (null when the tab never launched). Sub-second values indicate the tab bounced without
     * the user ever seeing a page; minutes indicate a genuine user abandon.
     * [recreatedAfterProcessDeath] is true when the managing activity was restored from saved
     * state — the OS killed the app process while the browser was in the foreground.
     */
    data class Event(
        val type: EventType,
        val atEpochMs: Long,
        val tabElapsedMs: Long?,
        val recreatedAfterProcessDeath: Boolean,
        val hadResponseUri: Boolean,
        val detail: String?
    )

    @Volatile
    private var last: Event? = null

    /** The most recent hosted-UI event, or null if none occurred in this process. */
    val lastEvent: Event? get() = last

    /** Returns the most recent event and clears it, so stale state never bleeds into a later attempt. */
    fun consumeLastEvent(): Event? {
        val event = last
        last = null
        return event
    }

    internal fun record(
        type: EventType,
        tabElapsedMs: Long? = null,
        recreatedAfterProcessDeath: Boolean = false,
        hadResponseUri: Boolean = false,
        detail: String? = null
    ) {
        last = Event(
            type = type,
            atEpochMs = System.currentTimeMillis(),
            tabElapsedMs = tabElapsedMs,
            recreatedAfterProcessDeath = recreatedAfterProcessDeath,
            hadResponseUri = hadResponseUri,
            detail = detail
        )
    }
}
