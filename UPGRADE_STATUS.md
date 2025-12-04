# Upgrade Status: v2.26.3 → v2.30.4

**Date**: December 3, 2024  
**Branch**: `upgrade-to-2.30.4`  
**Status**: 🟡 **IN PROGRESS** - Merge complete, compilation errors present

---

## Phase 1: Merge Conflicts - ✅ COMPLETED

### Summary
- **Total Conflicts**: 43 files
- **Resolution Strategy**: Strategic merge preserving multi-account features
- **Commit**: `dac5b8fa` - "Merge upstream v2.30.4 - Phase 1: Resolve conflicts"

### Files Resolved

#### Build Configuration (20 files) - Accepted Upstream
- All `build.gradle.kts` files
- Updated to upstream versions (infrastructure changes)

#### Core Plugin Files - Mixed Strategy
| File | Strategy | Reason |
|------|----------|--------|
| `RealAWSCognitoAuthPlugin.kt` | **THEIRS** (upstream) | Complete UseCase refactor (2800→621 lines) |
| `AWSCognitoAuthPlugin.kt` | **OURS** (partial) | Kept multi-account API signatures |
| `AuthStateMachine.kt` | **OURS** | Custom AuthStateRepo integration |
| `KotlinAuthFacadeInternal.kt` | **OURS** | Multi-account facade logic |

#### Credential Store - Kept Ours
| File | Strategy | Multi-Account Feature |
|------|----------|----------------------|
| `AWSCognitoAuthCredentialStore.kt` | **OURS** | Per-user credential keys |
| `AWSCognitoLegacyCredentialStore.kt` | **OURS** | Legacy migration support |

#### State Machine Data - Kept Ours  
| File | Strategy | Multi-Account Feature |
|------|----------|----------------------|
| `AmplifyCredential.kt` | **OURS** | Custom token structures |
| `SignedInData.kt` | **OURS** | User-specific data |

#### Actions - Kept Ours (7 files)
- `AuthorizationCognitoActions.kt`
- `DeleteUserCognitoActions.kt`
- `DeviceSRPCognitoSignInActions.kt`
- `HostedUICognitoActions.kt`
- `MigrateAuthCognitoActions.kt`
- `SRPCognitoActions.kt`
- `SignInChallengeHelper.kt`

#### Test Files (7 files) - Accepted Upstream
- All test files accepted from upstream

#### Core Category - Fixed Typo
- `core/src/main/java/com/amplifyframework/core/category/Category.java`

---

## Phase 2: Compilation Errors - 🔴 IN PROGRESS

### Build Command
```bash
./gradlew :aws-auth-cognito:compileDebugKotlin
```

### Error Summary
**Total Errors**: ~73 compilation errors

### Error Categories

#### 1. UseCase Helper Methods Missing (35 errors)
**Pattern**: `Unresolved reference 'requireSignedInState'`, `throwIfNotConfigured`

**Affected Files**:
- All UseCase files (AssociateWebAuthnCredentialUseCase, DeleteUserUseCase, etc.)

**Root Cause**: 
- Upstream RealAWSCognitoAuthPlugin has helper methods for UseCases
- We accepted upstream version which doesn't have our multi-account logic

**Fix Strategy**:
Need to restore or recreate helper methods in RealAWSCognitoAuthPlugin that UseCases depend on.

#### 2. Token Structure Changes (15 errors)
**Pattern**: `Unresolved reference 'tokenValue'`, `expiration`, `getUserSub'`

**Examples**:
```
SignOutCognitoActions.kt:78: Unresolved reference 'tokenValue'
SessionHelper.kt:34: Unresolved reference 'expiration'
SignInChallengeHelper.kt:63: Unresolved reference 'getUserSub'
```

**Root Cause**:
- Upstream changed token structures (JWT serialization breaking change #3162)
- Our code uses old token property names

**Fix Strategy**:
Update our code to use new token structure from `Tokens.kt`.

#### 3. Token Class Redeclaration (2 errors)
```
AmplifyCredential.kt:99: Redeclaration: data class CognitoUserPoolTokens
Tokens.kt:128: Redeclaration: data class CognitoUserPoolTokens
```

**Root Cause**:
- We have `CognitoUserPoolTokens` in our AmplifyCredential.kt
- Upstream now has it in Tokens.kt

**Fix Strategy**:
Remove our version, use upstream Tokens.kt.

#### 4. Method Signature Changes (8 errors)
**Examples**:
```
SRPCognitoActions.kt:95: None of the following candidates is applicable for getActiveUsername
KeyValueRepositoryFactory.kt:19: Unresolved reference 'AWS_KEY_VALUE_STORE_IDENTIFIER'
SignOutUseCase.kt:127: Argument type mismatch (Boolean vs String)
```

**Root Cause**:
- Upstream changed method signatures
- Constants moved or renamed

**Fix Strategy**:
Update call sites to match new signatures.

#### 5. JWTParser Missing (2 errors)
```
HostedUICognitoActions.kt:58: Unresolved reference 'JWTParser'
```

**Root Cause**:
- JWT parsing logic changed upstream

**Fix Strategy**:
Use new JWT parsing utilities from upstream.

---

## Next Steps

### Immediate Actions (Phase 2)

1. **Restore Helper Methods to RealAWSCognitoAuthPlugin** 🔴 **CRITICAL**
   ```kotlin
   // Need to add back:
   private fun requireSignedInState(): AuthenticationState.SignedIn
   private fun throwIfNotConfigured()
   ```

2. **Update Token References** 🔴 **HIGH**
   - Change `tokenValue` → `value`
   - Change `expiration` → `expiresAt` (or similar)
   - Use new token classes from `Tokens.kt`

3. **Remove Token Class Duplication** 🔴 **HIGH**
   - Delete `CognitoUserPoolTokens` from `AmplifyCredential.kt`
   - Import from `Tokens.kt` instead

4. **Fix Method Signature Mismatches** 🟡 **MEDIUM**
   - Update `getActiveUsername` calls
   - Fix `AWS_KEY_VALUE_STORE_IDENTIFIER` references
   - Correct argument order in `SignOutUseCase`

5. **Update JWT Parsing** 🟡 **MEDIUM**
   - Replace `JWTParser` usage with new upstream utilities

### Phase 3: Re-implement Multi-Account on UseCase Pattern

After compilation errors are fixed, we need to:

1. **Add userId parameter to UseCases**
   - Modify UseCase constructors or execute methods
   - Pass userId through the chain

2. **Integrate AuthStateRepo**
   - Ensure AuthStateRepo is still being used
   - Verify per-user state isolation

3. **Update queueFacade Calls**
   - Currently `AWSCognitoAuthPlugin` calls `queueFacade` with username/userId
   - Need to ensure these propagate to UseCases

### Phase 4: Testing

1. Build successfully
2. Run unit tests
3. Test multi-account flows manually
4. Regression testing

---

## Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| **UseCase pattern incompatibility** | 🔴 HIGH | May need significant refactoring of UseCases |
| **Token serialization breaking** | 🟡 MEDIUM | Update token property access patterns |
| **Lost multi-account state** | 🔴 HIGH | Verify AuthStateRepo still integrated |
| **API signature changes** | 🟡 MEDIUM | Update all call sites |
| **Test failures** | 🟢 LOW | Expected, fix incrementally |

---

## Backup & Rollback

All original implementations backed up in `.upgrade-backup/`:
- `RealAWSCognitoAuthPlugin.kt.ours` - Our 2800-line version
- `RealAWSCognitoAuthPlugin.kt.theirs` - Upstream 621-line version
- `multi-account-changes.patch` - Full diff of our changes
- `amplifyframework-backup/` - All modified files

**Rollback Command**:
```bash
git checkout main
git branch -D upgrade-to-2.30.4
```

---

## Time Estimate

- **Phase 2** (Fix compilation): 4-8 hours
- **Phase 3** (Re-implement multi-account): 8-16 hours
- **Phase 4** (Testing & fixes): 8-16 hours
- **Total**: 20-40 hours of focused development

---

## Conclusion

The merge is complete and conflicts are resolved strategically. We now have ~73 compilation errors to fix, primarily due to:

1. Upstream's UseCase pattern refactor
2. Token structure changes (JWT serialization breaking change)
3. Method signature updates

The good news:
- ✅ Our core multi-account files preserved (AuthStateRepo, credential stores, actions)
- ✅ Conflicts resolved systematically
- ✅ Full backup available for rollback
- ✅ Clear path forward identified

Next immediate step: Fix compilation errors starting with helper methods.





