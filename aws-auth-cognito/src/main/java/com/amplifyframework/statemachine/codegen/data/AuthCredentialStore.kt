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

package com.amplifyframework.statemachine.codegen.data

internal interface AuthCredentialStore {
    // Amplify Credentials
    fun saveCredential(credential: AmplifyCredential)
    fun retrieveCredential(userId: String? = null): AmplifyCredential
    fun deleteCredential(userId: String? = null)

    // Device Metadata
    fun saveDeviceMetadata(username: String, deviceMetadata: DeviceMetadata)
    fun retrieveDeviceMetadata(username: String): DeviceMetadata
    fun deleteDeviceKeyCredential(username: String)

    // ASF Device
    fun saveASFDevice(device: AmplifyCredential.ASFDevice)
    fun retrieveASFDevice(): AmplifyCredential.ASFDevice
    fun deleteASFDevice()
}
