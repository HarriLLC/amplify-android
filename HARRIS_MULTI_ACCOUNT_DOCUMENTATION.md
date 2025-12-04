# Harris LLC - Multi-Account Authentication Documentation

## AWS Amplify Android SDK Fork - Multiple Business Logins Support

---

## Table of Contents

1. [Overview](#overview)
2. [Repository Information](#repository-information)
3. [Architecture Changes](#architecture-changes)
4. [Core Components Modified](#core-components-modified)
5. [Data Structures](#data-structures)
6. [State Machine Changes](#state-machine-changes)
7. [API Changes](#api-changes)
8. [Credential Storage](#credential-storage)
9. [Usage Guide](#usage-guide)

---

## Overview

This fork of the AWS Amplify Android SDK (base version 2.25.0, tag **2.26.3**) implements **multi-account authentication support**, enabling applications to:

- Store and manage multiple user accounts simultaneously
- Switch between authenticated users without full re-authentication
- Maintain separate credential stores per user
- Support concurrent session management for business applications requiring multiple logins
- **Sign out all users at once** when needed

### Key Features

- **Per-user credential storage**: Each user's credentials are stored with a unique key based on their userId
- **State isolation**: Authentication states are managed per-user using a LIFO (Last-In-First-Out) map
- **Seamless user switching**: Users can switch between accounts without losing session data
- **Backward compatibility**: Existing single-account applications continue to work
- **Migration support**: Fallback to old credential keys for seamless app migration
- **Bulk sign-out**: Sign out all authenticated users with a single call

---

## Repository Information

| Property | Value |
|----------|-------|
| **Fork Repository** | https://github.com/HarriLLC/amplify-android.git |
| **Fork Tag** | `2.26.3` |
| **Base Version** | 2.25.0 (Amplify Android) |
| **Latest Commit** | `2e3ea6cfb62ea3f9d11f6d3e276d494682bb50c8` |
| **Commit Message** | "Support sign out for all users at once" |
| **Commit Date** | Tue May 20 16:01:59 2025 +0300 |
| **Author** | Rami Hussein |
| **Consumer App** | https://github.com/HarriLLC/AndroidTeamLive.git |
| **Consumer Branch** | `multiple_business_logins/merge_dev_into_base` |


---

## Architecture Changes

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Application Layer                             │
│                    (AndroidTeamLive App)                            │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Amplify Auth Category                           │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  AuthCategoryBehavior (Modified)                             │   │
│  │  - signOut(username, userId, ...)                           │   │
│  │  - fetchAuthSession(username, userId, ...)                  │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   RealAWSCognitoAuthPlugin                          │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  - Per-user state management                                 │   │
│  │  - Username-aware sign in/out flows                         │   │
│  │  - Session retrieval by user                                │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    StateMachineForAuth                              │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  AuthStateRepo (NEW)                                         │   │
│  │  ├── In-memory LifoMap<String, AuthState>                   │   │
│  │  └── Encrypted persistent storage per user                   │   │
│  └─────────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  LifoMap (NEW)                                               │   │
│  │  - Thread-safe LIFO map for state tracking                  │   │
│  │  - Supports multiple concurrent users                        │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   Credential Storage Layer                          │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  AWSCognitoAuthCredentialStore (Modified)                    │   │
│  │  - Per-user credential keys: {userId}_amplify.{poolId}.session│   │
│  │  - Backward compatible with single-user storage              │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Core Components Modified

### 1. New Files Created

#### `AuthStateRepo.kt`
**Location**: `aws-auth-cognito/src/main/java/com/amplifyframework/statemachine/codegen/data/AuthStateRepo.kt`

A singleton repository for managing per-user authentication states:

```kotlin
internal class AuthStateRepo private constructor(context: Context) {
    // In-memory LIFO map to store authentication states
    private val authStateMap = LifoMap.empty<String, AuthState>()
    
    // Encrypted key-value store for persisting authentication states
    private val encryptedStore = EncryptedKeyValueRepository(context, PREF_KEY)
    
    fun put(key: String, value: AuthState)      // Store state by username
    fun get(key: String): AuthState?            // Retrieve state by username
    fun remove(key: String)                     // Remove state by username
    fun activeState(): AuthState?               // Get most recent state
    fun activeStateKey(): String?               // Get most recent username
    fun getDefaultConfiguredState(): AuthState  // Get default configured state
}
```

**Key Features**:
- Stores `SignedIn` states persistently in encrypted storage
- Uses in-memory LIFO map for intermediate states during sign-in flow
- Clears in-memory map when session is established to allow new logins
- Thread-safe singleton pattern

#### `ThreadSafeLifoMap.kt` (LifoMap)
**Location**: `aws-auth-cognito/src/main/java/com/amplifyframework/statemachine/util/ThreadSafeLifoMap.kt`

A thread-safe map with LIFO (stack) behavior:

```kotlin
class LifoMap<K, V>(private val maxSize: Int? = null) {
    fun push(key: K, value: V)     // Add/update entry
    fun pop(): V?                   // Remove and return most recent
    fun pop(key: K): V?            // Remove specific key
    fun peek(): V?                  // View most recent without removing
    fun peekKey(): K?              // Get most recent key
    fun get(key: K): V?            // Get value by key
    fun containsKey(key: K): Boolean
    fun clear()
}
```

#### `StateMachineForAuth.kt`
**Location**: `aws-auth-cognito/src/main/java/com/amplifyframework/statemachine/StateMachineForAuth.kt`

Extended state machine with per-user state management:

```kotlin
internal open class StateMachineForAuth(
    resolver: StateMachineResolver<AuthState>,
    val environment: AuthEnvironment,
    ...
) : EventDispatcher {
    
    private val authStateRepo: AuthStateRepo = AuthStateRepo.getInstance(environment.context)
    
    // Get state for specific user
    private fun getAuthStateForUser(username: String?, ignoreUsername: Boolean = false): AuthState
    
    // Set state for specific user
    private fun setAuthState(userName: String, value: AuthState)
    
    // Send event with username context
    override fun send(event: StateMachineEvent, username: String, ignoreUsername: Boolean)
    
    // Listen for state changes with username context
    fun listen(username: String, token: StateChangeListenerToken, listener: (AuthState) -> Unit, ...)
    
    // Get current state for specific user
    fun getCurrentState(username: String, completion: (AuthState) -> Unit)
}
```

### 2. Modified Core Files

#### `AuthCategoryBehavior.java`
**Changes**: Added overloaded methods with `userId` parameter

```java
// New method signatures
void fetchAuthSession(
    @NonNull String userId,
    @NonNull Consumer<AuthSession> onSuccess,
    @NonNull Consumer<AuthException> onError
);

void signOut(
    @NonNull String userId, 
    @NonNull Consumer<AuthSignOutResult> onComplete
);

void signOut(
    @NonNull String userId,
    @NonNull AuthSignOutOptions options,
    @NonNull Consumer<AuthSignOutResult> onComplete
);
```

#### `AuthSignInResult.java`
**Changes**: Added `username` and `userId` fields to sign-in results

```java
public final class AuthSignInResult {
    private final boolean isSignedIn;
    private final AuthNextSignInStep nextStep;
    private final String userId;      // NEW
    private final String username;    // NEW
    
    // New constructor
    public AuthSignInResult(
        boolean isSignedIn, 
        String username,      // NEW
        String userId,        // NEW
        @NonNull AuthNextSignInStep nextStep
    )
    
    @Nullable public String getUserId()    // NEW
    @Nullable public String getUsername()  // NEW
}
```

#### `SignedInData.kt`
**Changes**: Added `email` field for user identification

```kotlin
@Serializable
internal data class SignedInData(
    val userId: String,
    val username: String,
    val signedInDate: Date,
    val signInMethod: SignInMethod,
    val cognitoUserPoolTokens: CognitoUserPoolTokens,
    val email: String? = null  // NEW - Used as key for multi-account
)
```

#### `SignOutData.kt`
**Changes**: Added `userId` and `signOutAllUsers` fields

```kotlin
internal data class SignOutData(
    val userId: String,              // NEW
    val globalSignOut: Boolean = false,
    val browserPackage: String? = null,
    val bypassCancel: Boolean = false,
    val signOutAllUsers: Boolean = false  // NEW - Sign out all users at once
)
```

#### `AmplifyCredential.kt`
**Changes**: Modified `Empty` class to include userId and clearAllSessions flag

```kotlin
@Serializable
@SerialName("empty")
data class Empty(
    val userId: String?, 
    val clearAllSessions: Boolean = false  // NEW - Flag to clear all user sessions
) : AmplifyCredential()
```

#### `CredentialType.kt`
**Changes**: Modified `Amplify` type to include userId

```kotlin
internal sealed class CredentialType {
    data class Amplify(val userId: String?) : CredentialType()  // Changed from object
    data class Device(val username: String) : CredentialType()
    object ASF : CredentialType()
}
```

---

## Data Structures

### State Storage Key Format

Credentials are stored with user-specific keys:

```
{userId}_amplify.{userPoolId}.{identityPoolId}.session
```

Example:
```
us-east-1_abc123_user456_amplify.us-east-1_XXXXXX.us-east-1:YYYYYY.session
```

### AuthState Serialization

The `AuthNAndAuthZ` structure is serialized for persistent storage:

```kotlin
@Serializable
private data class AuthNAndAuthZ(
    val authNState: AuthenticationState.SignedIn,
    val authZState: AuthorizationState.SessionEstablished
)
```

---

## State Machine Changes

### Event Modifications

All major events now include `userId` parameter for proper state routing:

| Event | Old Signature | New Signature |
|-------|--------------|---------------|
| `ConfigureAuth` | `ConfigureAuth(configuration)` | `ConfigureAuth(configuration, userId)` |
| `FetchUnAuthSession` | `FetchUnAuthSession` (object) | `FetchUnAuthSession(userId)` |
| `RefreshSession` | `RefreshSession(credential)` | `RefreshSession(userId, credential)` |
| `ThrowError` | `ThrowError(exception)` | `ThrowError(userId, exception)` |
| `DeleteUser` | `DeleteUser(accessToken)` | `DeleteUser(accessToken, userId, username)` |
| `SignOutLocally` | `SignOutLocally(signedInData, ...)` | `SignOutLocally(userId, signedInData, ..., signOutAllUsers)` |
| `SignOutGlobally` | `SignOutGlobally(signedInData, ...)` | `SignOutGlobally(userId, signedInData, ...)` |
| `RevokeToken` | `RevokeToken(signedInData, ...)` | `RevokeToken(userId, signedInData, ...)` |
| `ClearFederationToIdentityPool` | `ClearFederationToIdentityPool(id)` | `ClearFederationToIdentityPool(id, userId)` |

### EventDispatcher Interface

Extended to support username-based event dispatch:

```kotlin
interface EventDispatcher {
    fun send(event: StateMachineEvent)
    fun send(event: StateMachineEvent, username: String, ignoreUsername: Boolean = false)  // NEW
}
```

---

## API Changes

### Sign In Flow

```kotlin
// Sign in returns username and userId in result
Amplify.Auth.signIn(username, password, { result ->
    if (result.isSignedIn) {
        val userId = result.userId      // Available for multi-account tracking
        val username = result.username  // Available for multi-account tracking
    }
}, { error -> })
```

### Sign Out Flow

```kotlin
// Sign out specific user
Amplify.Auth.signOut(userId, { result ->
    // User signed out
})

// Sign out with options
Amplify.Auth.signOut(userId, AuthSignOutOptions.builder()
    .globalSignOut(true)
    .build(), { result -> })

// NEW: Sign out ALL users at once
Amplify.Auth.signOut(
    userId = "",
    signOutAllUsers = true,
    options = AuthSignOutOptions.builder().build()
) { result -> 
    // All users signed out
}
```

### Fetch Session Flow

```kotlin
// Fetch session for specific user
Amplify.Auth.fetchAuthSession(userId, { session ->
    // Session for specific user
}, { error -> })
```

---

## Credential Storage

### AWSCognitoAuthCredentialStore Changes

```kotlin
internal class AWSCognitoAuthCredentialStore(...) : AuthCredentialStore {
    
    companion object {
        // Regex pattern to match all user session keys
        private const val SESSION_KEY_REGEX = "^[a-fA-F0-9-]+_amplify.*\\.session$"
    }
    
    // Save credential with user-specific key OR clear all sessions
    override fun saveCredential(credential: AmplifyCredential) {
        // NEW: Support clearing all user sessions at once
        if (credential is AmplifyCredential.Empty && credential.clearAllSessions) {
            keyValue.removeAll(SESSION_KEY_REGEX)
            return
        }
        
        val userId = when (credential) {
            is AmplifyCredential.UserPool -> credential.signedInData.userId
            is AmplifyCredential.IdentityPool -> credential.identityId
            else -> null
        }
        val sessionKey = userId?.let { generateKeyWithPrefix(it + "_", Key_Session) }
        keyValue.put(sessionKey ?: generateKey(Key_Session), serializeCredential(credential))
    }
    
    // Retrieve credential with user-specific key (with fallback for migration)
    override fun retrieveCredential(userId: String?): AmplifyCredential {
        if (userId == null) {
            return deserializeCredential(null, keyValue.get(generateKey(Key_Session)))
        }
        // NEW: Fallback to old key format for migration support
        return deserializeCredential(userId, keyValue.get(generateKeyWithPrefix(userId + "_", Key_Session)))
            .takeIf { it !is AmplifyCredential.Empty }
            ?: deserializeCredential(null, keyValue.get(generateKey(Key_Session)))
    }
    
    // Delete credential with user-specific key
    override fun deleteCredential(userId: String?) {
        userId?.let {
            keyValue.remove(generateKeyWithPrefix(it + "_", Key_Session))
        } ?: keyValue.remove(generateKey(Key_Session))
    }
}
```

### AuthCredentialStore Interface

```kotlin
internal interface AuthCredentialStore {
    fun saveCredential(credential: AmplifyCredential)
    fun retrieveCredential(userId: String? = null): AmplifyCredential  // Added userId param
    fun deleteCredential(userId: String? = null)                        // Added userId param
    // ... other methods unchanged
}
```

---

## Usage Guide

### Basic Multi-Account Usage

```kotlin
// 1. Sign in first user
Amplify.Auth.signIn("user1@example.com", "password1", { result1 ->
    val user1Id = result1.userId
    val user1Name = result1.username
    
    // Store these for later use
    saveUserCredentials(user1Name, user1Id)
    
    // 2. Sign in second user (first user's session is preserved)
    Amplify.Auth.signIn("user2@example.com", "password2", { result2 ->
        val user2Id = result2.userId
        val user2Name = result2.username
        
        saveUserCredentials(user2Name, user2Id)
    }, { error -> })
}, { error -> })

// 3. Fetch session for specific user
fun getSessionForUser(userId: String) {
    Amplify.Auth.fetchAuthSession(userId, { session ->
        if (session.isSignedIn) {
            // Use session for API calls
        }
    }, { error -> })
}

// 4. Sign out specific user
fun signOutUser(userId: String) {
    Amplify.Auth.signOut(userId, { result ->
        when (result) {
            is AWSCognitoAuthSignOutResult.CompleteSignOut -> {
                // User fully signed out
            }
            is AWSCognitoAuthSignOutResult.PartialSignOut -> {
                // Handle partial sign out
            }
            is AWSCognitoAuthSignOutResult.FailedSignOut -> {
                // Handle failure
            }
        }
    })
}

// 5. Sign out ALL users at once (NEW)
fun signOutAllUsers() {
    Amplify.Auth.signOut(
        userId = "",
        signOutAllUsers = true,
        options = AuthSignOutOptions.builder().build()
    ) { result ->
        // All users signed out, all sessions cleared
    }
}
```

### Session Management Pattern

```kotlin
class MultiAccountAuthManager {
    private val activeUsers = mutableMapOf<String, UserCredentials>()
    
    data class UserCredentials(
        val username: String,
        val userId: String
    )
    
    fun signIn(email: String, password: String, onResult: (Result<UserCredentials>) -> Unit) {
        Amplify.Auth.signIn(email, password, { result ->
            if (result.isSignedIn) {
                val credentials = UserCredentials(
                    username = result.username ?: email,
                    userId = result.userId ?: ""
                )
                activeUsers[credentials.userId] = credentials
                onResult(Result.success(credentials))
            }
        }, { error ->
            onResult(Result.failure(error))
        })
    }
    
    fun getSession(userId: String, onResult: (AuthSession?) -> Unit) {
        Amplify.Auth.fetchAuthSession(userId, { session ->
            onResult(session)
        }, { error ->
            onResult(null)
        })
    }
    
    fun signOut(userId: String, onResult: (Boolean) -> Unit) {
        Amplify.Auth.signOut(userId, { result ->
            if (result is AWSCognitoAuthSignOutResult.CompleteSignOut) {
                activeUsers.remove(userId)
                onResult(true)
            } else {
                onResult(false)
            }
        })
    }
    
    // NEW: Sign out all users and clear local tracking
    fun signOutAll(onResult: (Boolean) -> Unit) {
        Amplify.Auth.signOut(
            userId = "",
            signOutAllUsers = true,
            options = AuthSignOutOptions.builder().build()
        ) { result ->
            if (result is AWSCognitoAuthSignOutResult.CompleteSignOut) {
                activeUsers.clear()
                onResult(true)
            } else {
                onResult(false)
            }
        }
    }
}
```

---

## Appendix

### Files Modified Summary

| Category | Files Modified | New Files |
|----------|---------------|-----------|
| Core Auth | 17 | 0 |
| State Machine | 12 | 3 |
| Actions | 10 | 0 |
| Events | 5 | 0 |
| Data Classes | 8 | 2 |
| Tests | 10 | 0 |
| Build Config | 25 | 0 |
| Core Store | 2 | 0 |
| **Total** | **89** | **5** |

### Commit Statistics

- **Total files changed**: 95
- **Lines added**: ~1,892
- **Lines removed**: ~481
- **Net change**: +1,411 lines

---

## Contact & Support

- **Fork Maintainer**: samikhleaf
- **Organization**: Harris LLC
- **Consumer App Repository**: https://github.com/HarriLLC/AndroidTeamLive.git

---

*Document updated: May 20, 2025*
*Based on tag: 2.26.3*
*Latest commit: 2e3ea6cfb62ea3f9d11f6d3e276d494682bb50c8*

