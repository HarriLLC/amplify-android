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

package com.amplifyframework.auth.cognito.actions

import com.amplifyframework.auth.cognito.AuthEnvironment
import com.amplifyframework.auth.exceptions.ValidationException
import com.amplifyframework.statemachine.Action
import com.amplifyframework.statemachine.codegen.actions.AuthenticationActions
import com.amplifyframework.statemachine.codegen.data.AmplifyCredential
import com.amplifyframework.statemachine.codegen.data.CredentialType
import com.amplifyframework.statemachine.codegen.data.DeviceMetadata
import com.amplifyframework.statemachine.codegen.data.SignInData
import com.amplifyframework.statemachine.codegen.data.SignInMethod
import com.amplifyframework.statemachine.codegen.data.SignedInData
import com.amplifyframework.statemachine.codegen.data.SignedOutData
import com.amplifyframework.statemachine.codegen.events.AuthEvent
import com.amplifyframework.statemachine.codegen.events.AuthenticationEvent
import com.amplifyframework.statemachine.codegen.events.SignInEvent
import com.amplifyframework.statemachine.codegen.events.SignOutEvent
import kotlinx.serialization.StringFormat

internal object AuthenticationCognitoActions : AuthenticationActions {
    override fun configureAuthenticationAction(event: AuthenticationEvent.EventType.Configure) =
        Action<AuthEnvironment>("ConfigureAuthN") { id, dispatcher ->
            logger.verbose("$id Starting execution")
            val evt = when (val credentials = event.storedCredentials) {
                is AmplifyCredential.UserPoolTypeCredential -> {
                    val deviceDataCredentials = (
                        credentialStoreClient.loadCredentials(
                            CredentialType.Device(credentials.signedInData.username)
                        ) as? AmplifyCredential.DeviceData
                        )?.deviceMetadata ?: DeviceMetadata.Empty
                    AuthenticationEvent(
                        AuthenticationEvent.EventType.InitializedSignedIn(
                            credentials.signedInData,
                            deviceDataCredentials
                        )
                    )
                }
                is AmplifyCredential.IdentityPoolFederated -> {
                    AuthenticationEvent(AuthenticationEvent.EventType.InitializedFederated)
                }
                else -> AuthenticationEvent(AuthenticationEvent.EventType.InitializedSignedOut(SignedOutData()))
            }
            logger.verbose("$id Sending event ${evt.type}")
            dispatcher.send(evt)

            val authEvent = AuthEvent(
                AuthEvent.EventType.ConfiguredAuthentication(event.configuration, event.storedCredentials)
            )
            logger.verbose("$id Sending event ${authEvent.type}")
            dispatcher.send(authEvent)
        }

    override fun initiateSignInAction(event: AuthenticationEvent.EventType.SignInRequested) =
        Action<AuthEnvironment>("InitiateSignInAction") { id, dispatcher ->
            logger.verbose("$id Starting execution")

            val evt = when (val data = event.signInData) {
                is SignInData.SRPSignInData -> {
                    if (data.username != null && data.password != null) {
                        SignInEvent(
                            SignInEvent.EventType.InitiateSignInWithSRP(
                                data.username,
                                data.password,
                                data.metadata,
                                data.authFlowType
                            )
                        )
                    } else {
                        AuthenticationEvent(
                            AuthenticationEvent.EventType.ThrowError(
                                ValidationException("Sign in failed.", "username or password empty")
                            )
                        )
                    }
                }
                is SignInData.CustomAuthSignInData -> {
                    if (data.username != null) {
                        SignInEvent(
                            SignInEvent.EventType.InitiateSignInWithCustom(data.username, data.metadata)
                        )
                    } else {
                        AuthenticationEvent(
                            AuthenticationEvent.EventType.ThrowError(
                                ValidationException("Sign in failed.", "username can not be empty")
                            )
                        )
                    }
                }
                is SignInData.CustomSRPAuthSignInData -> {
                    if (data.username != null && data.password != null) {
                        SignInEvent(
                            SignInEvent.EventType.InitiateCustomSignInWithSRP(
                                data.username,
                                data.password,
                                data.metadata
                            )
                        )
                    } else {
                        AuthenticationEvent(
                            AuthenticationEvent.EventType.ThrowError(
                                ValidationException("Sign in failed.", "username can not be empty")
                            )
                        )
                    }
                }
                is SignInData.HostedUISignInData -> {
                    SignInEvent(SignInEvent.EventType.InitiateHostedUISignIn(data))
                }
                is SignInData.MigrationAuthSignInData -> {
                    if (data.username != null && data.password != null) {
                        SignInEvent(
                            SignInEvent.EventType.InitiateMigrateAuth(
                                username = data.username,
                                password = data.password,
                                metadata = data.metadata,
                                authFlowType = data.authFlowType
                            )
                        )
                    } else {
                        AuthenticationEvent(
                            AuthenticationEvent.EventType.ThrowError(
                                ValidationException("Sign in failed.", "username or password empty")
                            )
                        )
                    }
                }
                is SignInData.UserAuthSignInData -> {
                    if (data.username != null) {
                        SignInEvent(
                            SignInEvent.EventType.InitiateUserAuth(
                                data.username,
                                data.preferredChallenge,
                                data.callingActivity,
                                data.metadata
                            )
                        )
                    } else {
                        AuthenticationEvent(
                            AuthenticationEvent.EventType.ThrowError(
                                ValidationException("Sign in failed.", "username cannot be empty")
                            )
                        )
                    }
                }

                is SignInData.AutoSignInData -> {
                    SignInEvent(SignInEvent.EventType.InitiateAutoSignIn(data))
                }
            }

            logger.verbose("$id Sending event ${evt.type}")
            dispatcher.send(evt)
        }

    override fun initiateSignOutAction(
        userId: String,
        event: AuthenticationEvent.EventType.SignOutRequested,
        signedInData: SignedInData?
    ) = Action<AuthEnvironment>("InitSignOut") { id, dispatcher ->
        logger.verbose("$id Starting execution")

        val evt = when {
            signedInData != null && signedInData.signInMethod is SignInMethod.HostedUI -> {
                SignOutEvent(SignOutEvent.EventType.InvokeHostedUISignOut(userId, event.signOutData, signedInData))
            }
            signedInData != null &&
                signedInData.signInMethod == SignInMethod.ApiBased(SignInMethod.ApiBased.AuthType.UNKNOWN) &&
                hostedUIClient != null -> {
                /*
                If sign in method is unknown, this is due to SignInMethod not being tracked in Amplify v1. We try to
                assume that hosted ui sign in may have been used if hostedUIClient is configured. This only happens if
                a customers configuration contained a valid Oauth section, complete with signOutRedirectURI.
                 */
                SignOutEvent(SignOutEvent.EventType.InvokeHostedUISignOut(userId, event.signOutData, signedInData))
            }
            signedInData != null && event.signOutData.globalSignOut -> {
                SignOutEvent(SignOutEvent.EventType.SignOutGlobally(userId, signedInData))
            }
            signedInData != null && !event.signOutData.globalSignOut -> {
                SignOutEvent(SignOutEvent.EventType.RevokeToken(userId, signedInData))
            }
            else -> SignOutEvent(SignOutEvent.EventType.SignOutLocally(userId, signedInData))
        }
        logger.verbose("$id Sending event ${evt.type}")
        dispatcher.send(evt)
    }
}
