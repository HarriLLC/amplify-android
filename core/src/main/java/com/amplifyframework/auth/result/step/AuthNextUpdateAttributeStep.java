/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package com.amplifyframework.auth.result.step;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.ObjectsCompat;

import com.amplifyframework.auth.AuthCodeDeliveryDetails;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * This object represents all details around the next step in the user attribute update process. It holds
 * an instance of the {@link AuthUpdateAttributeStep} enum to denote the step itself and supplements it with
 * additional details which can optionally accompany it. If there is no next step, {@link #getUpdateAttributeStep()}
 * will have a value of DONE.
 */
public final class AuthNextUpdateAttributeStep {
    private final AuthUpdateAttributeStep updateAttributeStep;
    private final Map<String, String> additionalInfo;
    private final AuthCodeDeliveryDetails codeDeliveryDetails;

    /**
     * Gives details on the next step, if there is one, in the update attribute flow.
     * @param updateAttributeStep the next step in the user attribute update flow (could be optional or required)
     * @param additionalInfo possible extra info to go with the next step (refer to plugin documentation)
     * @param codeDeliveryDetails Details about how a code was sent, if relevant to the current step
     */
    public AuthNextUpdateAttributeStep(
            @NonNull AuthUpdateAttributeStep updateAttributeStep,
            @NonNull Map<String, String> additionalInfo,
            @Nullable AuthCodeDeliveryDetails codeDeliveryDetails) {
        this.updateAttributeStep = Objects.requireNonNull(updateAttributeStep);
        this.additionalInfo = new HashMap<>();
        this.additionalInfo.putAll(Objects.requireNonNull(additionalInfo));
        this.codeDeliveryDetails = codeDeliveryDetails;
    }

    /**
     * Returns the next step in the user attribute update flow (could be optional or required).
     * @return the next step in the user attribute update flow (could be optional or required)
     */
    @NonNull
    public AuthUpdateAttributeStep getUpdateAttributeStep() {
        return updateAttributeStep;
    }

    /**
     * Returns possible extra info to go with the next step (refer to plugin documentation).
     * @return possible extra info to go with the next step (refer to plugin documentation)
     */
    @Nullable
    public Map<String, String> getAdditionalInfo() {
        return additionalInfo;
    }

    /**
     * Details about how a code was sent, if relevant to the current step.
     * @return Details about how a code was sent, if relevant to the current step - null otherwise
     */
    @Nullable
    public AuthCodeDeliveryDetails getCodeDeliveryDetails() {
        return codeDeliveryDetails;
    }

    /**
     * When overriding, be sure to include updateAttributeStep, additionalInfo, and codeDeliveryDetails in the hash.
     * @return Hash code of this object
     */
    @Override
    public int hashCode() {
        return ObjectsCompat.hash(
                getUpdateAttributeStep(),
                getAdditionalInfo(),
                getCodeDeliveryDetails()
        );
    }

    /**
     * When overriding, be sure to include updateAttributeStep, additionalInfo, and codeDeliveryDetails
     * in the comparison.
     * @return True if the two objects are equal, false otherwise
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if (obj == null || getClass() != obj.getClass()) {
            return false;
        } else {
            AuthNextUpdateAttributeStep authUpdateAttributeResult = (AuthNextUpdateAttributeStep) obj;
            return ObjectsCompat.equals(getUpdateAttributeStep(), authUpdateAttributeResult.getUpdateAttributeStep()) &&
                    ObjectsCompat.equals(getAdditionalInfo(), authUpdateAttributeResult.getAdditionalInfo()) &&
                    ObjectsCompat.equals(getCodeDeliveryDetails(), authUpdateAttributeResult.getCodeDeliveryDetails());
        }
    }

    /**
     * When overriding, be sure to include updateAttributeStep, additionalInfo, and codeDeliveryDetails
     * in the output string.
     * @return A string representation of the object
     */
    @Override
    public String toString() {
        return "AuthNextUpdateAttributeStep{" +
                "updateAttributeStep=" + getUpdateAttributeStep() +
                ", additionalInfo=" + getAdditionalInfo() +
                ", codeDeliveryDetails=" + getCodeDeliveryDetails() +
                '}';
    }
}
