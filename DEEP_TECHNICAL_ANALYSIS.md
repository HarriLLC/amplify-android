# Deep Technical Analysis & Upgrade Guide
## Harris Multi-Account Authentication Fork

**Document Version**: 1.0  
**Fork Tag**: 2.26.3  
**Base Version**: 2.25.0  
**Target Upstream**: 2.30.4  
**Analysis Date**: December 3, 2024

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Fork Changes - Complete Technical Breakdown](#fork-changes---complete-technical-breakdown)
3. [Upstream Changes Analysis (2.25.0 → 2.30.4)](#upstream-changes-analysis-2250--2304)
4. [Architectural Divergence Deep Dive](#architectural-divergence-deep-dive)
5. [File-by-File Change Analysis](#file-by-file-change-analysis)
6. [Merge Conflict Matrix](#merge-conflict-matrix)
7. [Upgrade Strategy & Implementation Plan](#upgrade-strategy--implementation-plan)
8. [Testing & Validation Requirements](#testing--validation-requirements)

---

## Executive Summary

### Change Magnitude

| Metric | Fork Changes | Upstream Changes | Conflict Potential |
|--------|-------------|------------------|-------------------|
| **Files Modified** | 95 files | ~2,500 lines in RealAWSCognitoAuthPlugin alone | 🔴 **HIGH** |
| **Net Lines** | +1,411 lines | -2,043 lines (massive refactor) | 🔴 **CRITICAL** |
| **State Machine** | Custom multi-user implementation | Race condition fixes | 🔴 **HIGH** |
| **Auth Plugin** | Modified for multi-account | **Refactored to UseCase pattern** | 🔴 **CRITICAL** |
| **API Surface** | +3 new method overloads | Multiple new features | 🟡 **MEDIUM** |

### Critical Findings

1. **🔴 BLOCKER**: Upstream moved ALL auth operations to UseCase pattern (3017, 3015, 3008, 3007, 2996, 2995, 2992, 2984, 2979)
2. **🔴 HIGH**: RealAWSCognitoAuthPlugin reduced from ~2,800 lines to ~500 lines  
3. **🟡 MEDIUM**: State machine race conditions fixed upstream (#3126)
4. **🟡 MEDIUM**: JWT token serialization breaking change (#3162)
5. **🟢 LOW**: New features (passwordless, email MFA) don't conflict with multi-account logic

---

## Fork Changes - Complete Technical Breakdown

### 1. Core State Management Architecture

#### 1.1 AuthStateRepo - NEW FILE

**Path**: `aws-auth-cognito/src/main/java/com/amplifyframework/statemachine/codegen/data/AuthStateRepo.kt`  
**Lines**: 159 (entirely new)  
**Purpose**: Centralized multi-user state management

**Key Implementation Details**:

```kotlin
internal class AuthStateRepo private constructor(context: Context) {
    // LIFO map for transient states during authentication flows
    private val authStateMap = LifoMap.empty<String, AuthState>()
    
    // Encrypted persistent storage for established sessions
    private val encryptedStore = EncryptedKeyValueRepository(
        context,
        PREF_KEY = "com.amplifyframework.statemachine.codegen.data.AuthStateRepo"
    )
    
    fun put(key: String, value: AuthState) {
        // Critical logic: Only persist established sessions
        if (value.isSessionEstablished) {
            encryptedStore.put(
                key,
                serializeAuthNAndZState(
                    AuthNAndAuthZ(
                        value.authNState as AuthenticationState.SignedIn,
                        value.authZState as AuthorizationState.SessionEstablished
                    )
                )
            )
            // Clear memory to allow new login
            authStateMap.clear()
            return
        }
        
        // Handle sign-out
        if (value.isSignedOut) {
            remove(key)
            return
        }
        
        // Store transient states in memory only
        authStateMap.push(key, value)
    }
    
    // Serialized format for persistence
    @Serializable
    private data class AuthNAndAuthZ(
        val authNState: AuthenticationState.SignedIn,
        val authZState: AuthorizationState.SessionEstablished
    )
}
```

**Technical Rationale**:
- **Two-tier storage**: Memory for flows, encrypted for sessions
- **Key-based isolation**: Each user identified by username/userId
- **LIFO behavior**: Most recent user always accessible via `activeState()`

**Impact on Upgrade**:
- ✅ **No Upstream Equivalent**: This file is entirely custom
- ⚠️ **Must Preserve**: Core to multi-account functionality
- 🔍 **Test**: Ensure state transitions still work after upstream merge

---

#### 1.2 LifoMap (ThreadSafeLifoMap) - NEW FILE

**Path**: `aws-auth-cognito/src/main/java/com/amplifyframework/statemachine/util/ThreadSafeLifoMap.kt`  
**Lines**: 129 (entirely new)  
**Purpose**: Thread-safe stack-like map for user session tracking

**Key Implementation**:

```kotlin
class LifoMap<K, V>(private val maxSize: Int? = null) {
    private val map = LinkedHashMap<K, V>()  // Maintains insertion order
    
    @Synchronized
    fun push(key: K, value: V) {
        if (maxSize != null && map.size >= maxSize) {
            map.remove(map.keys.first())  // Remove oldest if at capacity
        }
        map[key] = value
    }
    
    @Synchronized
    fun peek(): V? {
        val lastKey = map.keys.lastOrNull() ?: return null
        return map[lastKey]
    }
    
    @Synchronized
    fun peekKey(): K? = map.keys.lastOrNull()
}
```

**Thread Safety**:
- All operations use `@Synchronized` annotation
- LinkedHashMap provides insertion-order iteration
- LIFO access via `lastOrNull()` on keys

**Performance Characteristics**:
- O(1) for push, peek, get
- O(n) for pop (must re-index LinkedHashMap)
- Memory: O(n) where n = number of concurrent users

**Impact on Upgrade**:
- ✅ **No Upstream Equivalent**
- ⚠️ **Must Preserve**
- 🔍 **Consider**: Upstream state machine race fixes may need similar synchronization

---

#### 1.3 StateMachineForAuth - MODIFIED FILE

**Path**: `aws-auth-cognito/src/main/java/com/amplifyframework/statemachine/StateMachineForAuth.kt`  
**Lines**: +230 new lines  
**Base**: Extended from StateMachine.kt

**Major Changes**:

```kotlin
internal open class StateMachineForAuth(
    resolver: StateMachineResolver<AuthState>,
    val environment: AuthEnvironment,
    private val dispatcherQueue: CoroutineDispatcher = Dispatchers.Default,
    private val executor: EffectExecutor = ConcurrentEffectExecutor(dispatcherQueue),
    private val initialState: AuthState? = null
) : EventDispatcher {
    
    // NEW: Inject AuthStateRepo
    private val authStateRepo: AuthStateRepo = AuthStateRepo.getInstance(environment.context)
    
    // NEW: User-specific state retrieval
    private fun getAuthStateForUser(username: String?, ignoreUsername: Boolean = false): AuthState {
        if (username.isNullOrEmpty() || ignoreUsername) {
            return _state.value
        }
        return authStateRepo.get(username) ?: authStateRepo.getDefaultConfiguredState()
    }
    
    // NEW: User-specific state storage
    private fun setAuthState(userName: String, value: AuthState) {
        if (userName.isNotEmpty()) {
            authStateRepo.put(userName, value)
        }
        // Reset to default after session established
        _state.value = if (value.isSessionEstablished) 
            authStateRepo.getDefaultConfiguredState() 
        else value
    }
    
    // MODIFIED: Event dispatcher now accepts username
    override fun send(event: StateMachineEvent, username: String, ignoreUsername: Boolean) {
        stateMachineScope.launch {
            process(username, event, ignoreUsername)
        }
    }
    
    // MODIFIED: Listener subscription per-user
    fun listen(
        username: String,
        token: StateChangeListenerToken,
        listener: (AuthState) -> Unit,
        onSubscribe: OnSubscribedCallback?
    ) {
        stateMachineScope.launch {
            addSubscription(username, token, listener, onSubscribe)
        }
    }
    
    // NEW: Get current state for specific user
    fun getCurrentState(username: String, completion: (AuthState) -> Unit) {
        stateMachineScope.launch {
            completion(getAuthStateForUser(username))
        }
    }
}
```

**Critical Design Decisions**:

1. **State Isolation**: Each user maintains independent state
2. **Reset on Session Established**: Allows next user to sign in
3. **Backward Compatible**: Default methods still work for single-account usage

**Upstream Conflict Analysis**:

Upstream Commit #3126: "Fix race conditions in read state machine states"

**Changes in Upstream StateMachine.kt**:
```kotlin
// Upstream added:
private val stateLock = Mutex()

suspend fun getCurrentState(): State {
    stateLock.withLock {
        return _state.value
    }
}
```

**Conflict**: Our fork uses `stateMachineScope.launch` without Mutex  
**Resolution Strategy**: Adopt Mutex pattern for thread-safe state reads

---

### 2. Credential Storage Modifications

#### 2.1 AWSCognitoAuthCredentialStore - MAJOR MODIFICATIONS

**Path**: `aws-auth-cognito/src/main/java/com/amplifyframework/auth/cognito/data/AWSCognitoAuthCredentialStore.kt`  
**Lines Modified**: ~40 lines

**Key Changes**:

```kotlin
companion object {
    // NEW: Regex for bulk session removal
    private const val SESSION_KEY_REGEX = "^[a-fA-F0-9-]+_amplify.*\\.session$"
}

override fun saveCredential(credential: AmplifyCredential) {
    // NEW: Handle bulk clear
    if (credential is AmplifyCredential.Empty && credential.clearAllSessions) {
        keyValue.removeAll(SESSION_KEY_REGEX)
        return
    }
    
    // MODIFIED: Extract userId from credential
    val userId = when (credential) {
        is AmplifyCredential.UserPool -> credential.signedInData.userId
        is AmplifyCredential.IdentityPool -> credential.identityId
        else -> null
    }
    
    // MODIFIED: Generate user-specific key
    val sessionKey = userId?.let { generateKeyWithPrefix(it + "_", Key_Session) }
    keyValue.put(sessionKey ?: generateKey(Key_Session), serializeCredential(credential))
}

override fun retrieveCredential(userId: String?): AmplifyCredential {
    if (userId == null) {
        return deserializeCredential(null, keyValue.get(generateKey(Key_Session)))
    }
    
    // NEW: Fallback mechanism for migration
    return deserializeCredential(userId, keyValue.get(generateKeyWithPrefix(userId + "_", Key_Session)))
        .takeIf { it !is AmplifyCredential.Empty }
        ?: deserializeCredential(null, keyValue.get(generateKey(Key_Session)))
}
```

**Key Format Evolution**:

| Version | Format | Example |
|---------|--------|---------|
| **Original** | `amplify.{poolId}.session` | `amplify.us-east-1_ABC123.session` |
| **Fork v1** | `{username}_amplify.{poolId}.session` | `user@example.com_amplify.us-east-1_ABC123.session` |
| **Fork v2** | `{userId}_amplify.{poolId}.session` | `us-east-1_ABC123_123456_amplify.us-east-1_ABC123.session` |

**Migration Logic**:
1. Try user-specific key first
2. Fallback to default key if not found
3. Allows seamless migration from single to multi-account

**Upstream Changes**: None in credential store  
**Conflict Potential**: 🟢 **LOW**

---

#### 2.2 KeyValueRepository Interface - MODIFIED

**Path**: `core/src/main/java/com/amplifyframework/core/store/KeyValueRepository.kt`  
**Lines Modified**: +2 lines

```kotlin
interface KeyValueRepository {
    fun put(dataKey: String, value: String?)
    fun get(dataKey: String): String?
    fun remove(dataKey: String)
    fun removeAll() = Unit                    // NEW: Clear all keys
    fun removeAll(keyRegex: String) = Unit    // NEW: Clear matching keys
}
```

**EncryptedKeyValueRepository Implementation**:

```kotlin
override fun removeAll(keyRegex: String) {
    val keysToRemove = sharedPreferences.all.keys.filter { 
        it.matches(Regex(keyRegex)) 
    }
    if (keysToRemove.isEmpty()) return
    
    edit {
        keysToRemove.forEach { remove(it) }
    }
}
```

**Usage**: Powers the `signOutAllUsers` feature

**Upstream Changes**: None  
**Conflict Potential**: 🟢 **LOW** (interface addition only)

---

### 3. Event System Modifications

#### 3.1 Event Type Changes - PARAMETER ADDITIONS

All events now carry `userId` for proper routing:

| Event Class | Old Signature | New Signature | Impact |
|-------------|--------------|---------------|--------|
| **AuthEvent.EventType.ConfigureAuth** | `ConfigureAuth(config)` | `ConfigureAuth(config, userId)` | 🟡 Initialization |
| **AuthorizationEvent.EventType.FetchUnAuthSession** | `object FetchUnAuthSession` | `FetchUnAuthSession(userId)` | 🟡 Session mgmt |
| **AuthorizationEvent.EventType.RefreshSession** | `RefreshSession(credential)` | `RefreshSession(userId, credential)` | 🟡 Session refresh |
| **AuthorizationEvent.EventType.ThrowError** | `ThrowError(exception)` | `ThrowError(userId, exception)` | 🟢 Error handling |
| **DeleteUserEvent.EventType.DeleteUser** | `DeleteUser(accessToken)` | `DeleteUser(accessToken, userId, username)` | 🟡 Account deletion |
| **SignOutEvent.EventType.SignOutLocally** | `SignOutLocally(signedInData, ...)` | `SignOutLocally(userId, signedInData, ..., signOutAllUsers)` | 🔴 Sign out |

**Pattern**: Every event that operates on user state now includes userId

**Upstream Impact**: Upstream doesn't modify events  
**Conflict Potential**: 🟢 **LOW** (only additions)

---

#### 3.2 SignOutData - NEW FIELD

```kotlin
internal data class SignOutData(
    val userId: String,                      // ADDED
    val globalSignOut: Boolean = false,
    val browserPackage: String? = null,
    val bypassCancel: Boolean = false,
    val signOutAllUsers: Boolean = false     // ADDED
)
```

**signOutAllUsers** Flow:
1. Set `signOutAllUsers = true` in SignOutData
2. Propagated to SignOutEvent.EventType.SignOutLocally
3. Triggers `AmplifyCredential.Empty(userId, clearAllSessions = true)`
4. Executes `keyValue.removeAll(SESSION_KEY_REGEX)`
5. All user sessions cleared from encrypted storage

**Upstream**: No changes to SignOutData  
**Conflict Potential**: 🟢 **LOW**

---

### 4. Data Model Changes

#### 4.1 AmplifyCredential.Empty - CHANGED FROM OBJECT TO DATA CLASS

**Before** (Upstream):
```kotlin
@Serializable
@SerialName("empty")
object Empty : AmplifyCredential()
```

**After** (Fork):
```kotlin
@Serializable
@SerialName("empty")
data class Empty(
    val userId: String?, 
    val clearAllSessions: Boolean = false
) : AmplifyCredential()
```

**Breaking Change**: Yes  
**Serialization Impact**: Format changed from singleton to data class  
**Migration**: All `AmplifyCredential.Empty` references need userId parameter

**Upstream**: No changes  
**Conflict Potential**: 🟢 **LOW**

---

#### 4.2 SignedInData - ADDED FIELD

```kotlin
@Serializable
internal data class SignedInData(
    val userId: String,
    val username: String,
    val signedInDate: Date,
    val signInMethod: SignInMethod,
    val cognitoUserPoolTokens: CognitoUserPoolTokens,
    val email: String? = null  // ADDED - Used as state key
)
```

**Usage**: `email` field used for state management keys and event routing

**Upstream**: No changes to SignedInData  
**Conflict Potential**: 🟢 **LOW**

---

### 5. API Surface Changes

#### 5.1 AuthCategoryBehavior - NEW METHODS

```java
// NEW: Per-user session retrieval
void fetchAuthSession(
    @NonNull String userId,
    @NonNull Consumer<AuthSession> onSuccess,
    @NonNull Consumer<AuthException> onError
);

// NEW: Per-user sign out
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

**Implementation Strategy**: Method overloading  
**Backward Compatibility**: ✅ Original methods unchanged

**Upstream Changes**: None to these specific methods  
**Conflict Potential**: 🟢 **LOW**

---

#### 5.2 AuthSignInResult - NEW FIELDS

```java
public final class AuthSignInResult {
    private final boolean isSignedIn;
    private final AuthNextSignInStep nextStep;
    private final String userId;      // NEW
    private final String username;    // NEW
}
```

**Constructor Addition**:
```java
public AuthSignInResult(
    boolean isSignedIn, 
    String username,      // NEW parameter
    String userId,        // NEW parameter
    @NonNull AuthNextSignInStep nextStep
)
```

**Backward Compatibility**: ✅ Original constructor maintained

**Upstream**: No changes  
**Conflict Potential**: 🟢 **LOW**

---

### 6. Action Layer Modifications

All action classes modified to accept `userId` parameter:

**AuthenticationActions Interface**:
```kotlin
interface AuthenticationActions {
    // MODIFIED signature
    fun initiateSignOutAction(
        userId: String,                    // ADDED
        event: AuthenticationEvent.EventType.SignOutRequested,
        signedInData: SignedInData?,
        signOutAllUsers: Boolean           // ADDED
    ): Action
}
```

**Implementation Pattern Across All Actions**:
1. Accept `userId` parameter
2. Pass to event constructors
3. Route via `dispatcher.send(evt, username)`

**Modified Action Files** (10 files):
- AuthCognitoActions.kt
- AuthenticationCognitoActions.kt
- AuthorizationCognitoActions.kt
- CredentialStoreCognitoActions.kt
- DeleteUserCognitoActions.kt
- FetchAuthSessionCognitoActions.kt
- HostedUICognitoActions.kt
- SignInCognitoActions.kt
- SignOutCognitoActions.kt
- (2 more)

**Upstream**: No structural changes  
**Conflict Potential**: 🟢 **LOW**

---

## Upstream Changes Analysis (2.25.0 → 2.30.4)

### Critical Upstream Refactoring

#### 1. UseCase Pattern Migration (CRITICAL CONFLICT)

**Affected Commits**:
- #3146: Move SignOut to usecase
- #3140: Move SignIn and ConfirmSignIn to use cases  
- #3017: Refactor fetchAuthSession to useCase
- #3015: Move deleteUser to use case
- #3008: Move auto-sign-in to usecase
- #3007: Move MFA Preferences to use cases
- #2996: Move signup functions to usecases
- #2995: Move password functions to usecases
- #2992: Migrate TOTP setup to use case classes
- #2984: Move Attribute management into usecase classes
- #2979: Create use cases for device management APIs

**Structural Change**:

**Before** (Fork's current state):
```kotlin
// RealAWSCognitoAuthPlugin.kt (~2,800 lines)
internal class RealAWSCognitoAuthPlugin(...) {
    fun signIn(...) {
        // 100+ lines of inline logic
    }
    
    fun signOut(...) {
        // 80+ lines of inline logic
    }
    
    fun fetchAuthSession(...) {
        // 120+ lines of inline logic
    }
    
    // ... 20+ more methods
}
```

**After** (Upstream 2.30.4):
```kotlin
// RealAWSCognitoAuthPlugin.kt (~500 lines)
internal class RealAWSCognitoAuthPlugin(...) {
    private val useCaseFactory: AuthUseCaseFactory
    
    fun signIn(...) {
        useCaseFactory.signIn().execute(...)  // 5 lines
    }
    
    fun signOut(...) {
        useCaseFactory.signOut().execute(...) // 5 lines
    }
}

// NEW FILES:
// usecases/SignInUseCase.kt
// usecases/SignOutUseCase.kt
// usecases/FetchAuthSessionUseCase.kt
// ... 15+ more usecase files
```

**Impact Analysis**:

| Aspect | Fork Approach | Upstream Approach | Conflict Level |
|--------|--------------|-------------------|----------------|
| **Architecture** | Monolithic plugin class | UseCase delegation | 🔴 **CRITICAL** |
| **Line Count** | RealAWSCognitoAuthPlugin: ~2,800 lines | ~500 lines | 🔴 **MASSIVE** |
| **Testability** | Integration tests | Unit + Integration | 🟡 **MEDIUM** |
| **Multi-Account** | Embedded in methods | **Must adapt UseCases** | 🔴 **BLOCKER** |

**Merge Strategy Required**:
1. ✅ **Accept Upstream UseCase structure**
2. 🔧 **Adapt each UseCase to accept userId parameter**
3. 🔧 **Inject AuthStateRepo into UseCases**
4. 🔧 **Modify UseCase execute() methods for multi-account**

**Estimated Effort**: 40-60 hours

---

#### 2. State Machine Race Condition Fixes

**Commit**: #3126 "Fix race conditions in read state machine states"

**Changes**:
```kotlin
// OLD (Fork's current):
class StateMachine {
    private val _state = MutableStateFlow(initialState)
    
    fun getCurrentState(): State {
        return _state.value  // ⚠️ Race condition possible
    }
}

// NEW (Upstream):
class StateMachine {
    private val _state = MutableStateFlow(initialState)
    private val stateLock = Mutex()
    
    suspend fun getCurrentState(): State {
        stateLock.withLock {
            return _state.value  // ✅ Thread-safe
        }
    }
}
```

**Impact on Fork**:
- Our `StateMachineForAuth` extends StateMachine
- We added custom state management without Mutex
- **Must adopt**: Mutex pattern for `getAuthStateForUser()`

**Conflict Level**: 🔴 **HIGH**

**Resolution**:
```kotlin
// Apply to StateMachineForAuth.kt
private val userStateLock = Mutex()

private suspend fun getAuthStateForUser(username: String?): AuthState {
    userStateLock.withLock {
        if (username.isNullOrEmpty()) {
            return _state.value
        }
        return authStateRepo.get(username) ?: authStateRepo.getDefaultConfiguredState()
    }
}
```

---

#### 3. JWT Token Serialization Breaking Change

**Commit**: #3162 "Resolve logout issue caused by JWT token serialization breaking change"

**Issue**: Kotlin serialization library changed JWT deserialization format

**Impact**: Credentials stored with old format became unreadable

**Upstream Fix**:
```kotlin
// Added backward compatibility deserializer
internal class CognitoUserPoolTokensDeserializer : KSerializer<CognitoUserPoolTokens> {
    override fun deserialize(decoder: Decoder): CognitoUserPoolTokens {
        return try {
            // Try new format
            decoder.decodeSerializableValue(...)
        } catch (e: SerializationException) {
            // Fallback to old format
            decoder.decodeSerializableValue(legacySerializer)
        }
    }
}
```

**Fork Impact**: ✅ **AUTO-RESOLVED** (We use same serialization)  
**Action Required**: ⚠️ Ensure migration testing

---

#### 4. TypeSafe Auth Tokens

**Commit**: #3123 "Add typesafe classes for handling auth tokens"

**Changes**:
```kotlin
// OLD:
data class CognitoUserPoolTokens(
    val idToken: String?,
    val accessToken: String?,
    val refreshToken: String?
)

// NEW:
data class CognitoUserPoolTokens(
    val idToken: IdToken?,
    val accessToken: AccessToken?,
    val refreshToken: RefreshToken?
)

// New type wrappers:
@Serializable
@JvmInline
value class IdToken(val value: String)

@Serializable
@JvmInline
value class AccessToken(val value: String)
```

**Benefits**:
- Type safety at compile time
- Prevents mixing up tokens
- Zero runtime overhead (`@JvmInline`)

**Fork Impact**: 🟡 **MEDIUM**  
**Conflict**: Our `SignedInData.kt` uses `CognitoUserPoolTokens`  
**Resolution**: Adopt new types, update serialization

---

#### 5. AutoSignIn State Transition Fix

**Commit**: #3138 "Add missing state transitions for AutoSignIn state"

**Bug**: AutoSignIn could get stuck in intermediate state

**Fix**:
```kotlin
// Added transitions:
SignUpState.SignedUp -> AuthenticationState.SigningIn
AuthenticationState.SigningIn -> AuthenticationState.SignedIn
```

**Fork Impact**: ✅ **LOW** (We don't modify AutoSignIn)  
**Action**: Cherry-pick fix

---

#### 6. Session Identifier Preservation

**Commit**: #3136 "Fix losing session identifier when incorrect otp code is entered"

**Bug**: Session token lost on failed OTP, requiring restart

**Fix**: Preserve session in challenge state

**Fork Impact**: ✅ **LOW**  
**Action**: Cherry-pick fix

---

#### 7. Username Extraction Fix

**Commit**: #3149 "Fix issue with extracting username during USER_PASSWORD_AUTH"

**Bug**: Username not extracted correctly from JWT in PASSWORD_AUTH flow

**Fork Impact**: 🟡 **MEDIUM** (We use username for state keys)  
**Action**: **CRITICAL** - Cherry-pick immediately

---

#### 8. getCurrentUser Online Check Removal

**Commit**: #3122 "Remove online check from getCurrentUser"

**Change**: Removed network connectivity check before returning cached user

**Fork Impact**: ✅ **LOW**  
**Action**: Cherry-pick for consistency

---

#### 9. New Features (Non-Conflicting)

**Passwordless Auth** (#2952):
- New WebAuthn/passkey support
- Doesn't conflict with multi-account

**Email MFA** (#2935):
- Email-based MFA codes
- Doesn't conflict with multi-account

**Token Rotation** (#3112):
- Automatic refresh token rotation
- ✅ Compatible with multi-account

**Private Session WebUI** (#3108):
- iOS-style private browsing
- ✅ Compatible with multi-account

**OIDC Parameters** (#3158):
- Additional OAuth parameters
- ✅ Compatible with multi-account

---

## Architectural Divergence Deep Dive

### 1. Plugin Architecture Comparison

#### Fork Architecture (Current)

```
AWSCognitoAuthPlugin
    │
    ├─ configure() → RealAWSCognitoAuthPlugin(userId)
    │
    └─ RealAWSCognitoAuthPlugin
        ├─ authStateMachine: AuthStateMachine
        │   └─ StateMachineForAuth (custom)
        │       └─ AuthStateRepo (custom)
        │           └─ LifoMap (custom)
        │
        ├─ signIn() [150 lines]
        ├─ signOut() [100 lines]
        ├─ fetchAuthSession() [120 lines]
        └─ ... 20+ methods [~2,500 lines total]
```

#### Upstream Architecture (2.30.4)

```
AWSCognitoAuthPlugin
    │
    ├─ configure() → RealAWSCognitoAuthPlugin
    │                     + AuthUseCaseFactory
    │
    └─ RealAWSCognitoAuthPlugin [500 lines]
        ├─ useCaseFactory: AuthUseCaseFactory
        │   ├─ SignInUseCase
        │   ├─ SignOutUseCase
        │   ├─ FetchAuthSessionUseCase
        │   ├─ DeleteUserUseCase
        │   ├─ ... 15+ UseCases
        │   
        ├─ signIn() → useCaseFactory.signIn().execute() [5 lines]
        ├─ signOut() → useCaseFactory.signOut().execute() [5 lines]
        └─ ... delegates to UseCases
```

**Divergence Points**:

| Component | Fork | Upstream | Merge Strategy |
|-----------|------|----------|----------------|
| **Plugin Class** | 2,800 lines | 500 lines | Accept upstream, adapt UseCases |
| **Business Logic** | Inline in methods | Separate UseCase classes | Refactor our changes to UseCases |
| **State Machine** | Custom multi-user | Standard + race fix | Keep custom, adopt race fixes |
| **Dependency Injection** | Constructor params | UseCaseFactory | Adopt factory, inject custom components |

---

### 2. State Machine Architecture

#### Fork State Flow

```
User A signs in:
1. StateMachineForAuth.send(SignInEvent, username="userA")
2. getAuthStateForUser("userA") → AuthState.Configured
3. Process sign-in flow
4. setAuthState("userA", SignedIn(...))
5. AuthStateRepo.put("userA", SignedIn)
   ├─ Serializes to: userA@example.com_amplify.{poolId}.session
   ├─ Clears in-memory map
   └─ Resets _state to Configured

User B signs in:
1. StateMachineForAuth.send(SignInEvent, username="userB")
2. getAuthStateForUser("userB") → AuthState.Configured (fresh state)
3. Process sign-in flow independently
4. setAuthState("userB", SignedIn(...))
5. Both users now have persistent sessions
```

#### Upstream State Flow

```
User signs in:
1. StateMachine.send(SignInEvent)
2. _state transitions: Configured → SigningIn → SignedIn
3. Single state machine instance
4. Sign out clears state entirely
```

**Key Difference**: Fork maintains **multiple parallel states**, upstream has **single linear state**

---

### 3. Credential Storage Architecture

#### Storage Key Strategy

**Fork** (Multi-Account):
```
SharedPreferences keys:
- us-east-1_ABC_12345_amplify.us-east-1_ABC.us-east-1:123.session  (User A)
- us-east-1_ABC_67890_amplify.us-east-1_ABC.us-east-1:123.session  (User B)
- us-east-1_ABC_11111_amplify.us-east-1_ABC.us-east-1:123.session  (User C)

Retrieval:
1. Try: generateKeyWithPrefix(userId + "_", "session")
2. Fallback: generateKey("session")  // For migration
```

**Upstream** (Single Account):
```
SharedPreferences keys:
- amplify.us-east-1_ABC.us-east-1:123.session  (Only one)

Retrieval:
1. Get: generateKey("session")
```

**Conflict**: None (backward compatible)

---

## File-by-File Change Analysis

### Critical Files (High Conflict Potential)

| File | Fork Changes | Upstream Changes | Conflict Level | Resolution Strategy |
|------|-------------|------------------|----------------|---------------------|
| **RealAWSCognitoAuthPlugin.kt** | +413 lines (multi-account logic) | -2,288 lines (UseCase refactor) | 🔴 **CRITICAL** | Adopt UseCase structure, port multi-account to each UseCase |
| **StateMachineForAuth.kt** | +230 lines (new file) | Race condition fixes in base | 🔴 **HIGH** | Keep custom file, adopt Mutex pattern |
| **AuthStateRepo.kt** | +159 lines (new file) | N/A | ✅ **NONE** | No conflicts |
| **LifoMap.kt** | +129 lines (new file) | N/A | ✅ **NONE** | No conflicts |
| **AuthenticationState.kt** | +58 lines (userId routing) | No changes | 🟢 **LOW** | Simple merge |
| **AuthorizationState.kt** | +21 lines (userId routing) | No changes | 🟢 **LOW** | Simple merge |
| **AWSCognitoAuthCredentialStore.kt** | +33 lines (multi-user keys) | No structural changes | 🟢 **LOW** | Simple merge |
| **SignOutData.kt** | +3 lines (userId, signOutAllUsers) | No changes | ✅ **NONE** | No conflicts |
| **AmplifyCredential.kt** | Changed Empty to data class | No changes | 🟢 **LOW** | Simple merge |
| **AuthCategoryBehavior.java** | +3 method overloads | No changes | ✅ **NONE** | No conflicts |
| **AuthSignInResult.java** | +2 fields (userId, username) | No changes | ✅ **NONE** | No conflicts |

### Action Files (Medium Conflict)

| File | Fork Changes | Upstream Changes | Strategy |
|------|-------------|------------------|----------|
| **AuthenticationCognitoActions.kt** | +userId param | Moved to UseCases | Port changes to UseCases |
| **AuthorizationCognitoActions.kt** | +userId param | Moved to UseCases | Port changes to UseCases |
| **SignInCognitoActions.kt** | +userId param | Moved to UseCases | Port changes to UseCases |
| **SignOutCognitoActions.kt** | +userId + signOutAllUsers | Moved to UseCases | Port changes to UseCases |
| **FetchAuthSessionCognitoActions.kt** | +userId param | Moved to UseCases | Port changes to UseCases |

**Pattern**: All action files moved to UseCases in upstream. Must refactor multi-account logic into UseCase pattern.

---

## Merge Conflict Matrix

### Conflict Severity Levels

| Level | Symbol | Description | Estimated Resolution Time |
|-------|--------|-------------|--------------------------|
| **CRITICAL** | 🔴 | Complete architectural mismatch | 16-24 hours per file |
| **HIGH** | 🟠 | Significant structural differences | 8-12 hours per file |
| **MEDIUM** | 🟡 | Moderate conflicts | 4-6 hours per file |
| **LOW** | 🟢 | Simple line conflicts | 1-2 hours per file |
| **NONE** | ✅ | No conflicts | 0 hours |

### Conflict Matrix by Category

#### 1. Core Plugin Architecture

| Component | Conflict | Effort | Strategy |
|-----------|----------|--------|----------|
| AWSCognitoAuthPlugin.kt | 🟡 **MEDIUM** | 6h | Accept upstream, inject AuthStateRepo |
| RealAWSCognitoAuthPlugin.kt | 🔴 **CRITICAL** | 40h | Accept UseCase refactor, adapt ALL UseCases |
| AuthUseCaseFactory | 🔴 **CRITICAL** | 12h | Create custom factory with multi-account support |

**Total Effort**: ~58 hours

#### 2. State Machine

| Component | Conflict | Effort | Strategy |
|-----------|----------|--------|----------|
| StateMachine.kt | 🟠 **HIGH** | 8h | Accept upstream race fixes |
| StateMachineForAuth.kt | 🟠 **HIGH** | 10h | Adapt Mutex pattern, keep custom logic |
| AuthStateRepo.kt | ✅ **NONE** | 0h | Keep as-is |
| LifoMap.kt | ✅ **NONE** | 0h | Keep as-is |

**Total Effort**: ~18 hours

#### 3. Actions Layer

| Component | Conflict | Effort | Strategy |
|-----------|----------|--------|----------|
| All Action files (10 files) | 🔴 **CRITICAL** | 30h | Port userId logic to UseCases |

**Total Effort**: ~30 hours

#### 4. Events & Data

| Component | Conflict | Effort | Strategy |
|-----------|----------|--------|----------|
| Event classes (5 files) | 🟢 **LOW** | 3h | Simple merge (only additions) |
| Data classes (5 files) | 🟢 **LOW** | 2h | Simple merge (only additions) |
| States (4 files) | 🟢 **LOW** | 4h | Simple merge |

**Total Effort**: ~9 hours

#### 5. API Surface

| Component | Conflict | Effort | Strategy |
|-----------|----------|--------|----------|
| AuthCategoryBehavior.java | ✅ **NONE** | 0h | Keep overloads |
| AuthCategory.java | ✅ **NONE** | 0h | Keep delegation |
| AuthSignInResult.java | ✅ **NONE** | 0h | Keep fields |

**Total Effort**: ~0 hours

#### 6. Storage

| Component | Conflict | Effort | Strategy |
|-----------|----------|--------|----------|
| AWSCognitoAuthCredentialStore.kt | 🟢 **LOW** | 2h | Simple merge |
| KeyValueRepository.kt | ✅ **NONE** | 0h | Keep interface additions |
| EncryptedKeyValueRepository.kt | ✅ **NONE** | 0h | Keep implementation |

**Total Effort**: ~2 hours

### Grand Total Merge Effort

| Category | Hours |
|----------|-------|
| Plugin Architecture | 58h |
| State Machine | 18h |
| Actions → UseCases | 30h |
| Events & Data | 9h |
| API Surface | 0h |
| Storage | 2h |
| Testing & Validation | 40h |
| **TOTAL** | **157 hours** (~4 weeks) |

---

## Upgrade Strategy & Implementation Plan

### Phase 1: Preparation (Week 1)

#### 1.1 Create Feature Branch
```bash
git checkout -b upgrade/upstream-2.30.4
git fetch upstream
```

#### 1.2 Analyze UseCase Structure
```bash
# Extract all UseCase files from upstream
git diff 83605cb1..upstream/main --name-only | grep -i usecase

# Study each UseCase interface
git show upstream/main:aws-auth-cognito/src/main/java/com/amplifyframework/auth/cognito/usecases/
```

#### 1.3 Document Current Multi-Account Logic
Create mapping document:
```markdown
## Multi-Account Logic Mapping

### SignIn Flow
- Current location: RealAWSCognitoAuthPlugin.signIn() lines 565-771
- Multi-account additions:
  - Line 581: authStateMachine.getCurrentState(username.orEmpty())
  - Line 634: authStateMachine.listen(username.orEmpty(), token, ...)
  - Line 768: authStateMachine.send(event, username.orEmpty())
- Target location: SignInUseCase.execute()
- Required changes: [list specific changes]

### [Repeat for each method]
```

#### 1.4 Set Up Test Infrastructure
```bash
# Ensure all existing tests pass
./gradlew test
./gradlew connectedAndroidTest

# Create test coverage baseline
./gradlew koverHtmlReport
```

**Deliverables**:
- [ ] Feature branch created
- [ ] UseCase structure documented
- [ ] Logic mapping document
- [ ] Test baseline established

---

### Phase 2: UseCase Migration (Week 2-3)

#### 2.1 Accept Upstream UseCase Structure

```bash
# Cherry-pick UseCase infrastructure
git cherry-pick 3017  # fetchAuthSession usecase
git cherry-pick 3015  # deleteUser usecase
git cherry-pick 3008  # auto-sign-in usecase
# ... continue for all UseCase commits
```

**Expected Conflicts**: RealAWSCognitoAuthPlugin.kt

#### 2.2 Create Multi-Account UseCase Base

```kotlin
// NEW FILE: usecases/MultiAccountUseCase.kt
abstract class MultiAccountUseCase<Input, Output>(
    protected val authStateRepo: AuthStateRepo,
    protected val stateMachine: StateMachineForAuth
) {
    // Base functionality for multi-account operations
    protected suspend fun getStateForUser(userId: String): AuthState {
        return authStateRepo.get(userId) ?: authStateRepo.getDefaultConfiguredState()
    }
    
    protected suspend fun sendEventForUser(event: StateMachineEvent, userId: String) {
        stateMachine.send(event, userId)
    }
    
    abstract suspend fun execute(input: Input): Output
}
```

#### 2.3 Adapt Each UseCase (Priority Order)

**Priority 1 (Critical Path)**:
1. ✅ SignInUseCase
2. ✅ SignOutUseCase
3. ✅ FetchAuthSessionUseCase
4. ✅ GetCurrentUserUseCase

**Priority 2 (Common)**:
5. ConfirmSignInUseCase
6. ResetPasswordUseCase
7. SignUpUseCase

**Priority 3 (Advanced)**:
8. AutoSignInUseCase
9. DeleteUserUseCase
10. SetupTOTPUseCase

**Example Adaptation** (SignInUseCase):

```kotlin
// BEFORE (Upstream):
internal class SignInUseCase(
    private val stateMachine: AuthStateMachine,
    private val environment: AuthEnvironment
) {
    suspend fun execute(input: SignInInput): AuthSignInResult {
        return suspendCoroutine { continuation ->
            stateMachine.send(SignInEvent(...))
            // ... rest of logic
        }
    }
}

// AFTER (Multi-Account Fork):
internal class SignInUseCase(
    private val stateMachine: StateMachineForAuth,  // Changed type
    private val authStateRepo: AuthStateRepo,       // Added
    private val environment: AuthEnvironment
) : MultiAccountUseCase<SignInInput, AuthSignInResult>(authStateRepo, stateMachine) {
    
    suspend fun execute(input: SignInInput): AuthSignInResult {
        val userId = input.username?.let { extractUserIdFromUsername(it) } ?: ""
        
        return suspendCoroutine { continuation ->
            // Get state for this specific user
            val currentState = getStateForUser(userId)
            
            // Send event with user context
            sendEventForUser(SignInEvent(...), userId)
            
            // Listen for this user's state changes
            stateMachine.listen(userId, token, { state ->
                // ... process state changes
            }, { /* onSubscribe */ })
        }
    }
}
```

**Adaptation Checklist Per UseCase**:
- [ ] Accept `authStateRepo` in constructor
- [ ] Change `stateMachine` type to `StateMachineForAuth`
- [ ] Extract userId from input
- [ ] Use `getStateForUser(userId)` instead of global state
- [ ] Use `sendEventForUser(event, userId)` instead of global send
- [ ] Use `stateMachine.listen(userId, ...)` instead of global listen
- [ ] Update result to include userId/username if applicable

**Estimated Time**: 3-4 hours per UseCase × 15 UseCases = 45-60 hours

---

### Phase 3: State Machine Integration (Week 3)

#### 3.1 Apply Race Condition Fixes

```kotlin
// StateMachineForAuth.kt
private val userStateLock = Mutex()

private suspend fun getAuthStateForUser(username: String?, ignoreUsername: Boolean = false): AuthState {
    return userStateLock.withLock {  // Add Mutex
        if (username.isNullOrEmpty() || ignoreUsername) {
            return _state.value
        }
        authStateRepo.get(username) ?: authStateRepo.getDefaultConfiguredState()
    }
}

private suspend fun setAuthState(userName: String, value: AuthState) {
    userStateLock.withLock {  // Add Mutex
        if (userName.isNotEmpty()) {
            authStateRepo.put(userName, value)
        }
        _state.value = if (value.isSessionEstablished) 
            authStateRepo.getDefaultConfiguredState() 
        else value
    }
}
```

#### 3.2 Update State Machine Tests

```kotlin
// StateMachineForAuthTests.kt
@Test
fun `concurrent user sign ins should not interfere`() = runBlocking {
    val user1Job = launch { signIn("user1@example.com", "pass1") }
    val user2Job = launch { signIn("user2@example.com", "pass2") }
    
    user1Job.join()
    user2Job.join()
    
    val user1State = authStateRepo.get("user1@example.com")
    val user2State = authStateRepo.get("user2@example.com")
    
    assertTrue(user1State is AuthState.SignedIn)
    assertTrue(user2State is AuthState.SignedIn)
}
```

**Estimated Time**: 8-10 hours

---

### Phase 4: Cherry-Pick Critical Fixes (Week 3)

Apply upstream bug fixes that don't conflict:

```bash
# JWT serialization fix
git cherry-pick d2674378

# Username extraction fix (CRITICAL for multi-account)
git cherry-pick 8b2ef912

# AutoSignIn state transitions
git cherry-pick ef37f18c

# Session identifier preservation
git cherry-pick 71771e43

# getCurrentUser online check removal
git cherry-pick 0dbb4aaa
```

**Conflict Resolution**: Resolve manually, test each fix independently

**Estimated Time**: 8-12 hours

---

### Phase 5: Integration & Testing (Week 4)

#### 5.1 Unit Tests

```kotlin
// Test each UseCase independently
@Test
fun `SignInUseCase handles multiple users`() {
    // Test user A sign in
    // Test user B sign in
    // Verify both have separate states
}

@Test
fun `SignOutUseCase with signOutAllUsers clears all sessions`() {
    // Sign in 3 users
    // Sign out with signOutAllUsers=true
    // Verify all sessions cleared
}
```

#### 5.2 Integration Tests

```kotlin
@Test
fun `multiple users can sign in and fetch sessions independently`() {
    // Sign in user1
    val session1 = fetchAuthSession(userId="user1")
    
    // Sign in user2
    val session2 = fetchAuthSession(userId="user2")
    
    // Verify sessions are independent
    assertNotEquals(session1.userPoolTokens, session2.userPoolTokens)
}

@Test
fun `state machine handles concurrent sign in requests`() {
    // Concurrent sign ins
    // Verify no race conditions
    // Verify correct state isolation
}
```

#### 5.3 Instrumentation Tests

```kotlin
@Test
fun `end-to-end multi-account flow on device`() {
    // Real Cognito sign ins
    // Real credential storage
    // Real state persistence
    // App restart simulation
}
```

#### 5.4 Compatibility Tests

```kotlin
@Test
fun `migration from single-account to multi-account preserves session`() {
    // Simulate old credential format
    // Upgrade to new version
    // Verify session retrieved via fallback
}
```

**Test Coverage Target**: 90%+ for multi-account logic

**Estimated Time**: 30-40 hours

---

### Phase 6: Documentation & Release (Week 4)

#### 6.1 Update Documentation

- [ ] Update MULTI_ACCOUNT_OVERVIEW.md with new architecture
- [ ] Update HARRIS_MULTI_ACCOUNT_DOCUMENTATION.md with UseCase details
- [ ] Create UPGRADE_NOTES.md for consumers
- [ ] Update API examples in documentation

#### 6.2 Create Migration Guide for Consumers

```markdown
## Upgrading from 2.26.3 to 2.30.4-harris

### Breaking Changes
1. None - API surface unchanged

### New Features
- All upstream features (passwordless, email MFA, etc.)

### Testing Requirements
- Test multi-account flows
- Verify no regressions
```

#### 6.3 Version Tagging

```bash
git tag -a 2.30.4-harris.1 -m "Multi-account support on Amplify 2.30.4 base"
git push origin 2.30.4-harris.1
```

**Estimated Time**: 8-10 hours

---

## Testing & Validation Requirements

### Test Categories

#### 1. Multi-Account Core Functionality

**Test Suite**: `MultiAccountIntegrationTest.kt`

```kotlin
class MultiAccountIntegrationTest {
    @Test
    fun `sign in two users sequentially`()
    
    @Test
    fun `sign in three users, sign out middle user`()
    
    @Test
    fun `fetch session for specific user by userId`()
    
    @Test
    fun `sign out all users clears all credentials`()
    
    @Test
    fun `state isolation between users`()
    
    @Test
    fun `credential storage uses correct keys per user`()
    
    @Test
    fun `fallback retrieval for migrated credentials`()
}
```

#### 2. Concurrency & Race Conditions

**Test Suite**: `ConcurrencyTest.kt`

```kotlin
class ConcurrencyTest {
    @Test
    fun `concurrent sign ins without race conditions`()
    
    @Test
    fun `concurrent fetch sessions without race conditions`()
    
    @Test
    fun `concurrent sign outs without race conditions`()
    
    @Test
    fun `state machine mutex prevents data corruption`()
}
```

#### 3. Upstream Feature Compatibility

**Test Suite**: `UpstreamFeatureTest.kt`

```kotlin
class UpstreamFeatureTest {
    @Test
    fun `passwordless auth works with multi-account`()
    
    @Test
    fun `email MFA works with multi-account`()
    
    @Test
    fun `token rotation works per user`()
    
    @Test
    fun `private session WebUI works with multi-account`()
}
```

#### 4. Backward Compatibility

**Test Suite**: `BackwardCompatibilityTest.kt`

```kotlin
class BackwardCompatibilityTest {
    @Test
    fun `single account usage still works`()
    
    @Test
    fun `existing API methods unchanged`()
    
    @Test
    fun `credential migration from 2.26.3`()
}
```

#### 5. Edge Cases

**Test Suite**: `EdgeCaseTest.kt`

```kotlin
class EdgeCaseTest {
    @Test
    fun `handle 10+ concurrent users`()
    
    @Test
    fun `handle rapid sign in sign out cycles`()
    
    @Test
    fun `handle session expiration per user`()
    
    @Test
    fun `handle network failures per user`()
    
    @Test
    fun `handle device restart with multiple sessions`()
}
```

### Performance Benchmarks

```kotlin
class PerformanceTest {
    @Test
    fun `sign in performance with 5 users < 100ms overhead`()
    
    @Test
    fun `fetch session performance per user < 50ms`()
    
    @Test
    fun `memory usage with 10 users < 50MB`()
}
```

### Manual Test Checklist

- [ ] Install on physical device
- [ ] Sign in 3 different users
- [ ] Kill app, restart
- [ ] Verify all sessions persist
- [ ] Fetch session for each user
- [ ] Sign out one user
- [ ] Verify others remain signed in
- [ ] Sign out all users
- [ ] Verify all credentials cleared
- [ ] Test on Android 8, 10, 12, 14

---

## Appendix A: Upstream Commit Reference

### Major Feature Commits

| Commit | Version | Description | Impact |
|--------|---------|-------------|--------|
| 694dac08 | 2.30.4 | OIDC parameters support | 🟢 LOW |
| d2674378 | 2.30.4 | JWT serialization fix | 🟡 MEDIUM |
| 8b2ef912 | 2.30.3 | Username extraction fix | 🔴 CRITICAL |
| e7db5a79 | 2.30.2 | Move SignOut to usecase | 🔴 CRITICAL |
| 9c96c876 | 2.30.2 | Move SignIn to usecases | 🔴 CRITICAL |
| ef37f18c | 2.30.2 | AutoSignIn state fix | 🟡 MEDIUM |
| 71771e43 | 2.30.2 | Session identifier fix | 🟡 MEDIUM |
| 04748386 | 2.30.1 | TypeSafe auth tokens | 🟡 MEDIUM |
| 7fce1164 | 2.30.1 | State machine race fixes | 🔴 HIGH |
| 0dbb4aaa | 2.30.0 | Remove online check | 🟢 LOW |
| b8242c7f | 2.30.0 | Private session WebUI | 🟢 LOW |
| 3f72bcbd | 2.30.0 | Token rotation | 🟢 LOW |
| 44760282 | 2.27.3 | fetchAuthSession usecase | 🔴 CRITICAL |
| 179a95fb | 2.27.2 | deleteUser usecase | 🔴 CRITICAL |
| 71d90310 | 2.27.2 | auto-sign-in usecase | 🔴 CRITICAL |
| 5f9a3bc1 | 2.25.0 | Passwordless features | 🟢 LOW |
| f2673340 | 2.24.0 | Email MFA support | 🟢 LOW |

---

## Appendix B: File Change Summary

### New Files in Fork

1. `AuthStateRepo.kt` - 159 lines
2. `ThreadSafeLifoMap.kt` - 129 lines  
3. `StateMachineForAuth.kt` - 230 lines

**Total**: 518 lines of new infrastructure

### Modified Files in Fork

| Category | Count | Example Files |
|----------|-------|---------------|
| State Machine | 7 | StateMachine.kt, EventDispatcher.kt |
| Events | 5 | AuthEvent.kt, SignOutEvent.kt |
| Data | 6 | AmplifyCredential.kt, SignedInData.kt |
| States | 4 | AuthenticationState.kt, AuthorizationState.kt |
| Actions | 10 | SignInCognitoActions.kt, SignOutCognitoActions.kt |
| Plugin | 3 | AWSCognitoAuthPlugin.kt, RealAWSCognitoAuthPlugin.kt |
| API | 3 | AuthCategoryBehavior.java, AuthCategory.java |
| Storage | 3 | AWSCognitoAuthCredentialStore.kt, KeyValueRepository.kt |
| Tests | 8 | Various test files |

**Total Modified**: 49 files  
**Total New**: 3 files  
**Grand Total**: 52 files with custom logic

---

**Document End**

*For questions or clarifications, contact the Harris SDK team*





