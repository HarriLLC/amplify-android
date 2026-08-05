/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
package com.amplifyframework.auth.cognito.activities

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.amplifyframework.auth.cognito.HostedUiDiagnostics
import com.amplifyframework.core.Amplify

/**
 * Handles Hosted UI sign in with custom tabs
 */
internal class CustomTabsManagerActivity : Activity() {
    private var customTabsLaunched = false
    private var customTabsIntent: Intent? = null
    private var launchedAtRealtimeMs: Long = 0L
    private var recreatedAfterProcessDeath = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            extractState(intent.extras)
        } else {
            extractState(savedInstanceState)
            recreatedAfterProcessDeath = true
        }
    }

    override fun onResume() {
        super.onResume()

        /*
         * If this is the first run of the activity, launch the custom Chrome tab with HostedUI.
         * Note that we do not finish the activity at this point, in order to remain on the back
         * stack underneath the Chrome tab where they are going through the HostedUI flow.
         */
        if (!customTabsLaunched) {
            val tabIntent = customTabsIntent
            if (tabIntent == null) {
                // Custom tabs intent was lost (e.g. process death during the OAuth flow).
                // Cannot re-launch the browser, so finish gracefully; the auth redirect is
                // handled separately by HostedUIRedirectActivity.
                Log.w(TAG, "Custom tabs intent lost after process death, finishing.")
                HostedUiDiagnostics.record(
                    HostedUiDiagnostics.EventType.INTENT_LOST,
                    recreatedAfterProcessDeath = recreatedAfterProcessDeath
                )
                finish()
                return
            }
            try {
                startActivity(tabIntent)
            } catch (e: ActivityNotFoundException) {
                // No installed browser can handle the Custom Tab intent. Fail the sign-in
                // deterministically instead of crashing: the caller receives a cancellation
                // whose diagnostics identify the missing browser as the cause.
                Log.e(TAG, "No activity available to launch the hosted UI Custom Tab.", e)
                HostedUiDiagnostics.record(
                    HostedUiDiagnostics.EventType.LAUNCH_FAILED,
                    recreatedAfterProcessDeath = recreatedAfterProcessDeath,
                    detail = e.message
                )
                handleAuthorizationCanceled()
                finish()
                return
            }
            launchedAtRealtimeMs = SystemClock.elapsedRealtime()
            HostedUiDiagnostics.record(HostedUiDiagnostics.EventType.LAUNCHED)
            customTabsLaunched = true
            return
        }

        /*
         * If we are resuming this activity and the chrome tab has already been launched, this could be
         * due to HostedUI calling our internal link, or the user canceling the authorization flow. These
         * cases may be distinguished by checking whether a response URI is available, which would be provided
         * by CustomTabsRedirectActivity. If it is not, we have returned here due to the user
         * closing out of the custom Chrome tab which means we should return a user cancelled error.
         */
        val tabElapsedMs =
            if (launchedAtRealtimeMs > 0) SystemClock.elapsedRealtime() - launchedAtRealtimeMs else null
        if (intent.data != null) {
            HostedUiDiagnostics.record(
                HostedUiDiagnostics.EventType.COMPLETED,
                tabElapsedMs = tabElapsedMs,
                recreatedAfterProcessDeath = recreatedAfterProcessDeath,
                hadResponseUri = true
            )
            handleAuthorizationComplete()
        } else {
            Log.w(
                TAG,
                "Hosted UI returned without a response URI after ${tabElapsedMs}ms " +
                    "(recreatedAfterProcessDeath=$recreatedAfterProcessDeath); reporting user-cancelled."
            )
            HostedUiDiagnostics.record(
                HostedUiDiagnostics.EventType.CANCELLED,
                tabElapsedMs = tabElapsedMs,
                recreatedAfterProcessDeath = recreatedAfterProcessDeath
            )
            handleAuthorizationCanceled()
        }
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(CUSTOM_TABS_LAUNCHED_KEY, customTabsLaunched)
        outState.putParcelable(CUSTOM_TABS_INTENT_KEY, customTabsIntent)
        outState.putLong(CUSTOM_TABS_LAUNCHED_AT_KEY, launchedAtRealtimeMs)
    }

    private fun handleAuthorizationComplete() {
        Log.d(TAG, "Authorization flow completed successfully")
        setResult(RESULT_OK, intent)
    }

    private fun handleAuthorizationCanceled() {
        Log.d(TAG, "Authorization flow canceled by user")
        setResult(RESULT_CANCELED)
        Amplify.Auth.handleWebUISignInResponse(null)
    }

    private fun extractState(state: Bundle?) {
        if (state == null) {
            Log.d(TAG, "CustomTabsManagerActivity was created with a null state.")
            finish()
            return
        }
        customTabsIntent = state.getParcelable(CUSTOM_TABS_INTENT_KEY)
        customTabsLaunched = state.getBoolean(CUSTOM_TABS_LAUNCHED_KEY, false)
        launchedAtRealtimeMs = state.getLong(CUSTOM_TABS_LAUNCHED_AT_KEY, 0L)
    }

    companion object {
        private const val TAG = "AuthClient" // This activity is used for HostedUI auth flow
        const val CUSTOM_TABS_LAUNCHED_KEY = "customTabsLaunched"
        const val CUSTOM_TABS_INTENT_KEY = "customTabsIntent"
        const val CUSTOM_TABS_LAUNCHED_AT_KEY = "customTabsLaunchedAt"

        /**
         * Creates an intent to start an OAuth2 flow in Chrome custom tabs.
         * @param context the package context for the app.
         * @param customTabsIntent the intent for the Chrome custom tab.
         */
        fun createStartIntent(context: Context, customTabsIntent: Intent?): Intent {
            val intent = createBaseIntent(context)
            intent.putExtra(CUSTOM_TABS_INTENT_KEY, customTabsIntent)
            return intent
        }

        /**
         * Creates an intent to handle the completion of an authorization flow. This restores
         * the original CustomTabsManagerActivity that was created at the start of the flow.
         * @param context the package context for the app.
         * @param responseUri the response URI, which carries the parameters describing the response.
         */
        @JvmStatic
        fun createResponseHandlingIntent(context: Context, responseUri: Uri?): Intent {
            val intent = createBaseIntent(context)
            intent.data = responseUri
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return intent
        }

        private fun createBaseIntent(context: Context): Intent = Intent(context, CustomTabsManagerActivity::class.java)
    }
}
