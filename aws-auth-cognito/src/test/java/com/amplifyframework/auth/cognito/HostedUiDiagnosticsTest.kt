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

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Test

class HostedUiDiagnosticsTest {

    @Before
    fun setUp() {
        HostedUiDiagnostics.consumeLastEvent()
    }

    @Test
    fun `record exposes the event through lastEvent`() {
        HostedUiDiagnostics.record(
            HostedUiDiagnostics.EventType.CANCELLED,
            tabElapsedMs = 420,
            recreatedAfterProcessDeath = true
        )

        val event = HostedUiDiagnostics.lastEvent.shouldNotBeNull()
        event.type shouldBe HostedUiDiagnostics.EventType.CANCELLED
        event.tabElapsedMs shouldBe 420
        event.recreatedAfterProcessDeath shouldBe true
        event.hadResponseUri shouldBe false
        event.detail.shouldBeNull()
    }

    @Test
    fun `consumeLastEvent returns the event once and clears it`() {
        HostedUiDiagnostics.record(
            HostedUiDiagnostics.EventType.LAUNCH_FAILED,
            detail = "No Activity found to handle Intent"
        )

        val consumed = HostedUiDiagnostics.consumeLastEvent().shouldNotBeNull()
        consumed.type shouldBe HostedUiDiagnostics.EventType.LAUNCH_FAILED
        consumed.detail shouldBe "No Activity found to handle Intent"

        HostedUiDiagnostics.consumeLastEvent().shouldBeNull()
        HostedUiDiagnostics.lastEvent.shouldBeNull()
    }

    @Test
    fun `a new record overwrites the previous event`() {
        HostedUiDiagnostics.record(HostedUiDiagnostics.EventType.LAUNCHED)
        HostedUiDiagnostics.record(
            HostedUiDiagnostics.EventType.COMPLETED,
            tabElapsedMs = 90_000,
            hadResponseUri = true
        )

        val event = HostedUiDiagnostics.lastEvent.shouldNotBeNull()
        event.type shouldBe HostedUiDiagnostics.EventType.COMPLETED
        event.hadResponseUri shouldBe true
    }
}
