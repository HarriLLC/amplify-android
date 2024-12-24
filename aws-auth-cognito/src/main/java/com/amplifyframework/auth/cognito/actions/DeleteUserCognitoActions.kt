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

import aws.sdk.kotlin.services.cognitoidentityprovider.model.DeleteUserRequest
import aws.sdk.kotlin.services.cognitoidentityprovider.model.UserNotFoundException
import com.amplifyframework.auth.cognito.AuthEnvironment
import com.amplifyframework.statemachine.Action
import com.amplifyframework.statemachine.codegen.actions.DeleteUserActions
import com.amplifyframework.statemachine.codegen.data.SignOutData
import com.amplifyframework.statemachine.codegen.events.AuthenticationEvent
import com.amplifyframework.statemachine.codegen.events.AuthorizationEvent
import com.amplifyframework.statemachine.codegen.events.DeleteUserEvent

internal object DeleteUserCognitoActions : DeleteUserActions {
    override fun initDeleteUserAction(accessToken: String, userId: String): Action =
        Action<AuthEnvironment>("DeleteUser") { id, dispatcher ->
            logger.verbose("$id Starting execution")
            try {
                cognitoAuthService.cognitoIdentityProviderClient?.deleteUser(
                    DeleteUserRequest.invoke { this.accessToken = accessToken }
                )
                val evt = DeleteUserEvent(DeleteUserEvent.EventType.UserDeleted(userId = userId))
                logger.verbose("$id Sending event ${evt.type}")
                dispatcher.send(evt)
            } catch (e: Exception) {
                logger.warn("Failed to delete user.", e)
                if (e is UserNotFoundException) {
                    // The user could have been remotely deleted, clear local session
                    val evt = DeleteUserEvent(DeleteUserEvent.EventType.ThrowError(e, userId, true))
                    logger.verbose("$id Sending event ${evt.type}")
                    dispatcher.send(evt)
                } else {
                    val evt = DeleteUserEvent(DeleteUserEvent.EventType.ThrowError(e, userId, false))
                    logger.verbose("$id Sending event ${evt.type}")
                    dispatcher.send(evt)
                    val evt2 = AuthorizationEvent(AuthorizationEvent.EventType.ThrowError(userId, e))
                    logger.verbose("$id Sending event ${evt2.type}")
                    dispatcher.send(evt2)
                }
            }
        }

    override fun initiateSignOut(userId: String): Action =
        Action<AuthEnvironment>("Sign Out Deleted User") { id, dispatcher ->
            logger.verbose("$id Starting execution")
            val evt = AuthorizationEvent(AuthorizationEvent.EventType.UserDeleted())
            val evt2 = AuthenticationEvent(
                AuthenticationEvent.EventType.SignOutRequested(SignOutData(userId = userId, globalSignOut = true, bypassCancel = true))
            )
            logger.verbose("$id Sending event ${evt.type}")
            dispatcher.send(evt)
            logger.verbose("$id Sending event ${evt2.type}")
            dispatcher.send(evt2)
        }
}
