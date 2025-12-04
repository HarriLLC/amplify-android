# Upgrade Strategy: v2.26.3 → v2.30.4

## Conflict Resolution Plan

### Phase 1: Build Configuration (20 files)
**Strategy**: Accept upstream versions, these are infrastructure changes
- All build.gradle.kts files (version bumps, dependency updates)

### Phase 2: Core Multi-Account Files (23 files)
**Strategy**: Strategic merge preserving multi-account logic

#### Priority 1 - Critical Multi-Account Logic:
1. **RealAWSCognitoAuthPlugin.kt** 🔴 CRITICAL
   - Our changes: Multi-user support, userId parameter
   - Upstream: UseCase pattern refactor (2800 → 500 lines)
   - Strategy: Accept upstream structure, inject userId into UseCases

2. **AWSCognitoAuthPlugin.kt** 🔴 HIGH
   - Our changes: userId in configure()
   - Upstream: UseCaseFactory integration
   - Strategy: Merge both, pass userId to realPlugin

3. **AuthStateMachine.kt** 🔴 HIGH
   - Our changes: AuthStateRepo integration
   - Upstream: Race condition fixes
   - Strategy: Keep AuthStateRepo, adopt race fixes

4. **AWSCognitoAuthCredentialStore.kt** 🔴 HIGH
   - Our changes: Per-user credential keys
   - Upstream: Potential refactoring
   - Strategy: Keep per-user logic

5. **AuthorizationCognitoActions.kt** 🟡 MEDIUM
   - Check for state machine interactions

#### Priority 2 - State Machine Files:
6. **AmplifyCredential.kt** 🟡 MEDIUM
7. **SignedInData.kt** 🟡 MEDIUM
8. **KotlinAuthFacadeInternal.kt** 🟡 MEDIUM

#### Priority 3 - Test Files (8 files):
- Likely just test updates, accept upstream or merge

### Phase 3: Build and Fix Compilation Errors

### Phase 4: Adapt Multi-Account to UseCase Pattern

## Current Status
- Branch: `upgrade-to-2.30.4`
- Conflicts: 43 files
- Backup: `.upgrade-backup/`





