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

package com.amplifyframework.statemachine.codegen.actions

import com.amplifyframework.statemachine.Action
import com.amplifyframework.statemachine.codegen.events.SignOutEvent

internal interface SignOutActions {
    fun hostedUISignOutAction(userId: String, event: SignOutEvent.EventType.InvokeHostedUISignOut): Action
    fun localSignOutAction(event: SignOutEvent.EventType.SignOutLocally): Action
    fun globalSignOutAction(userId: String, event: SignOutEvent.EventType.SignOutGlobally): Action
    fun revokeTokenAction(event: SignOutEvent.EventType.RevokeToken): Action
    fun buildRevokeTokenErrorAction(event: SignOutEvent.EventType.SignOutGloballyError): Action
    fun userCancelledAction(event: SignOutEvent.EventType.UserCancelled): Action
}
