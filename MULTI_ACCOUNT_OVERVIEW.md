# Harris Multi-Account Authentication - Overview

## What We Built

A fork of **AWS Amplify Android SDK (v2.25.0)** that enables **multiple users to be authenticated simultaneously** within the same app.

---

## The Problem

The standard Amplify SDK supports only **one authenticated user at a time**. Signing in a new user automatically signs out the previous one.

## Our Solution

We modified the SDK to maintain **separate authentication states per user**, allowing users to:
- Log into multiple business accounts
- Switch between accounts instantly
- Maintain all sessions without re-authenticating
- Sign out all users at once when needed

---

## How It Works

### 1. Per-User Credential Storage
Each user's credentials are stored with a unique key:
```
{userId}_amplify.{poolId}.session
```

### 2. State Tracking with LIFO Map
A thread-safe "Last-In-First-Out" map tracks all active sessions, always knowing the most recently active user.

### 3. User ID-Based State Management
State management relies on `userId` instead of `username` for more reliable user identification.

### 4. User ID-Based APIs
Key methods now accept `userId` to target specific users:

```kotlin
// Sign out specific user
Amplify.Auth.signOut(userId) { result -> }

// Sign out ALL users at once
Amplify.Auth.signOut(userId = "", signOutAllUsers = true) { result -> }

// Get session for specific user
Amplify.Auth.fetchAuthSession(userId) { session -> }

// Sign-in returns user identifiers
Amplify.Auth.signIn(email, password) { result ->
    val userId = result.userId
    val username = result.username
}
```

### 5. Migration Support
Fallback mechanism retrieves credentials from old key format after migration.

---

## Key Components

| Component | Purpose |
|-----------|---------|
| `AuthStateRepo.kt` | Singleton that stores/retrieves per-user auth states |
| `LifoMap.kt` | Thread-safe map with stack behavior for session tracking |
| `StateMachineForAuth.kt` | Extended state machine routing events by userId |

---

## New Features in This Release (2.26.3)

| Feature | Description |
|---------|-------------|
| **Sign Out All Users** | New `signOutAllUsers` flag to clear all sessions at once |
| **User ID-Based Management** | Switched from username to userId for state tracking |
| **Migration Fallback** | Retrieves old credentials after app migration |
| **Bulk Session Clear** | `removeAll(regex)` clears all user sessions matching pattern |

---

## Modified Files (Summary)

- **Core APIs**: `AuthCategoryBehavior.java`, `AuthSignInResult.java`, `AuthCategory.java`
- **State Machine**: `StateMachineForAuth.kt`, `AuthenticationState.kt`, `AuthorizationState.kt`
- **Events**: All auth events now include `userId` and `signOutAllUsers` parameters
- **Storage**: `AWSCognitoAuthCredentialStore.kt` - per-user keys + bulk clear support
- **Data Classes**: `SignedInData.kt`, `SignOutData.kt`, `AmplifyCredential.kt`

**Total**: ~95 files modified, +1,892 lines added

---

## Version Info

| Property | Value |
|----------|-------|
| Fork Tag | `2.26.3` |
| Base Version | `2.25.0` |
| Latest Upstream | `2.30.4` |
| Commits Behind | ~113 |
| Fork Repo | `github.com/HarriLLC/amplify-android` |

---

## Consumer App

**Repository**: `github.com/HarriLLC/AndroidTeamLive`  
**Branch**: `multiple_business_logins/merge_dev_into_base`

---

See `HARRIS_MULTI_ACCOUNT_DOCUMENTATION.md` for full technical details.

---

**TL;DR**: Modified Amplify to store multiple user sessions instead of one, enabling seamless multi-account switching. Now includes ability to sign out all users at once.
