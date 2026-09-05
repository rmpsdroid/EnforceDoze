# EnforceDoze Fork — Authoritative Project Continuation

**Updated:** 2026-09-05 (Asia/Kolkata)
**Repository:** `rmpsdroid/EnforceDoze`
**Current authoritative Git state:** maintenance async restore/reapply remains fully
integrated on `master` / `origin/master` at
`d7c214d5a70fac2b26529f98cf9e259084564ad6`. Maintenance process-death recovery
is functionally complete on branch `fix/maintenance-process-death-v1` at commit
`d80a5ee` (`Fix maintenance process-death recovery`). That functional commit is built
and M30 runtime validated, but has not yet been documented, merged, or pushed.

**Purpose:** single source of truth for continuing this project in a new ChatGPT window without restarting the investigation.

---

# 0. HOW TO USE THIS FILE

This file consolidates:

1. the earlier **EnforceDoze Fork — Master Continuation Prompt**;
2. the later continuation snapshot that followed it;
3. the work completed during the latest 2026-08-28 audit session, including the Shizuku recovery fixes and the boot/reboot recovery liveness fix;
4. the current Git/device/test state;
5. the remaining audit list;
6. a reusable continuation prompt at the end.

## Supersession rule

**PART I and the FINAL CONTINUATION PROMPT are the current authoritative state.**

The historical source snapshots are retained later in this file so that no earlier technical detail, test observation, branch name, invariant, or design rationale is lost. Some of those historical snapshots contain statements such as old ADB endpoints, old `master` SHAs, “master must remain untouched,” “no merge,” or an old “next audit item.” Those statements describe the project **at that historical checkpoint** and are superseded by the newer state in PART I.

Do not treat old ADB endpoints as permanent. Wireless debugging ports change after reboot.

---

# PART I — CURRENT AUTHORITATIVE PROJECT STATE

# 1. PROJECT GOAL

This remains a long-running audit, hardening, modernization, device-testing, and eventual public-release project for an Android fork based on **EnforceDoze**.

The immediate phase is still reliability/correctness first:

- Doze entry and exit;
- screen on/off and unlock behavior;
- Shizuku and root execution;
- package suspension/restoration;
- notification disable/restore;
- physical device-state changes;
- sensor/biometric handling;
- maintenance behavior;
- async ordering and stale callbacks;
- durable state journals;
- generation/session ownership;
- process death and boot recovery;
- Android API/Samsung behavior;
- service lifecycle and recovery.

Use **release-oriented checkpoints** rather than endless micro-audits. Reliability
work must remain controlled and evidence-driven, but closely related changes may be
completed as one reviewed/build-tested candidate.

Public-release preparation, rebranding, and UI work may proceed in parallel with
remaining Phase-0 triage once no known critical reliability blocker is being left
unresolved. Do not destabilize known-good reliability architecture for UI work.

---

# 2. USER / REVIEW WORKFLOW

The user is not an Android developer and needs:

- exact commands;
- beginner-safe instructions;
- one controlled stage at a time;
- brief interpretation of every result;
- no assumption of Git/Gradle/ADB expertise;
- no huge command dumps during device testing.

Claude CLI is primarily used for implementation/debugging.

ChatGPT acts as independent reviewer and should:

1. inspect real source/diffs rather than trust summaries;
2. challenge implementation assumptions;
3. preserve known-good architecture;
4. reject unrelated redesign;
5. guide build/device testing;
6. inspect Git status and staged contents;
7. require explicit approval before commits;
8. require explicit approval before pushes;
9. require explicit approval before merges to `master`.

## Command formatting

When the prompt is:

`PS D:\AndroidProjects\EnforceDoze>`

give PowerShell commands appropriate for that shell.

When the prompt is:

`C:\adb>`

give only `adb.exe ...` style commands.

Proceed one stage at a time and wait for output during live testing.

---

# 3. OPERATING PRINCIPLES — MANDATORY

1. Preserve known-good architecture unless concrete evidence requires a change.
2. Prefer read-only investigation before modifications.
3. Make one controlled change at a time.
4. Do not combine unrelated bugs in one functional branch.
5. Validate after every meaningful change.
6. Prefer event-driven recovery over timers/polling.
7. Avoid broad locks unless necessary.
8. Do not clear durable restore debt merely to hide a failed restore.
9. Failed backend execution must preserve durable retry obligation where architecture allows.
10. Distinguish:
    - logically ACTIVE;
    - physically forced deep idle;
    - owned Doze session;
    - maintenance transition;
    - fresh entry;
    - deferred entry/recovery.
11. Do not expose secrets/passwords/API keys.
12. Do not commit temporary review `.diff`, `.patch`, or raw audit log files unless explicitly requested.
13. Never use `git add .` in this repository while protected untracked artifacts exist.
14. Before commit:
    - source review;
    - diff review;
    - `git diff --check`;
    - successful build;
    - relevant device test;
    - staged-content verification.
15. No commit without explicit **approve commit**.
16. No push without explicit **approve push**.
17. No merge to `master` without explicit **approve merge to master**.
18. No push of `master` without explicit **approve push master**.
19. Do not claim deterministic reproduction when only source structure or mechanics were validated.
20. Samsung lockscreen/AOD visual appearance is not proof of actual screen/keyguard/idle state.

---

# 4. REPOSITORY / PATHS

Upstream:

`Akylas/EnforceDoze`

Fork:

`rmpsdroid/EnforceDoze`

Windows repository:

`D:\AndroidProjects\EnforceDoze`

Application package:

`com.akylas.enforcedoze.fork`

Debug APK:

`D:\AndroidProjects\EnforceDoze\app\build\outputs\apk\debug\app-debug.apk`

Known app version from the earlier checkpoint:

- `versionCode=86`
- `versionName=1.10.2`
- `minSdk=23`
- `targetSdk=36`

Do not assume version metadata changed unless verified from the current source/build.

---

# 5. CURRENT GIT STATE — 2026-09-04

Current branch:

`master`

Current authoritative HEAD:

`fbc41b67b8e17dcc47a6bab93ecc818a34430e6d`

Maintenance async restore/reapply functional commit:

`434ce6508764bdcf2b34221f85858f9c0ce1a440`

Documentation commit:

`fbc41b67b8e17dcc47a6bab93ecc818a34430e6d`

Current synchronized refs:

```text
HEAD          = fbc41b67b8e17dcc47a6bab93ecc818a34430e6d
master        = fbc41b67b8e17dcc47a6bab93ecc818a34430e6d
origin/master = fbc41b67b8e17dcc47a6bab93ecc818a34430e6d
```

The maintenance async restore/reapply change is therefore:

**FUNCTIONALLY COMMITTED / DOCUMENTED / MERGED TO MASTER / MASTER PUSHED**

The local feature branch `fix/maintenance-async-reapply-v1` also currently
points to `fbc41b67b8e17dcc47a6bab93ecc818a34430e6d`. No feature-branch push was
required for the completed master result.

Functional production scope:

`app/src/main/java/com/akylas/enforcedoze/ForceDozeService.java`

Functional diff:

```text
1 file changed, 230 insertions(+), 24 deletions(-)
```

Candidate-5 source SHA256:

`41B99ED3C15B0FB7605081D67A63259AA537D08D968CBFF47EF84FDA1211ADF7`

Candidate-5 prebuild/final-runtime/staged diff SHA256:

`A3B351AA948998F5B05D9596B87B7C85702DD4735E806CF7284CA49B9769C6E3`

Frozen/installed Candidate-5 debug APK SHA256:

`3585AF7EA96B071F3BBFC345066F7B6FF369E516FE73F564E11B668B2D52A0CD`

Recent history:

```text
fbc41b6 Update continuation for maintenance async reapply
434ce65 Fix maintenance async restore reapply handling
885262e Finalize R0-6 continuation state
ae10255 Update continuation after R0-6 merge
```

Historical custom audit baseline remains relevant for provenance:

`audit/claude-current`

`fda26b14176480927db22271644a7096bbc9c285`

---

# 6. PROTECTED UNTRACKED FILES — DO NOT COMMIT

The repository contains a large body of local audit/review evidence. Treat all
existing untracked audit artifacts as protected unless explicitly reviewed and
approved for another action.

In particular, never casually stage, overwrite, rename, clean, or delete:

- all `r0-4-*` evidence;
- all `r0-5-*` evidence;
- all `r0-6-*` evidence;
- all `maintenance-async-reapply-*` evidence;
- earlier candidate/review/boot-restore artifacts already present in the
  worktree.

Important maintenance async restore/reapply evidence now includes:

```text
maintenance-async-reapply-build-candidate5.txt
maintenance-async-reapply-candidate1-prebuild-review.diff
maintenance-async-reapply-candidate2-pre-candidate3-review.diff
maintenance-async-reapply-candidate3-prebuild-review.diff
maintenance-async-reapply-candidate4-prebuild-review.diff
maintenance-async-reapply-candidate5-debug.apk
maintenance-async-reapply-candidate5-final-runtime-validated.diff
maintenance-async-reapply-candidate5-prebuild-review.diff
maintenance-async-reapply-candidate5-staged-final-review.diff
maintenance-async-reapply-project-continuation-pre-doc-update.md
maintenance-async-reapply-update-project-continuation.ps1
maintenance-async-reapply-update-project-continuation-v2.ps1
maintenance-async-reapply-project-continuation-pre-final-sync.md
maintenance-async-reapply-finalize-project-continuation.ps1
```

Protected maintenance pre-documentation backup SHA256:

`B898AD2B6253F46E2C2913460C0CD134802844DE3FC6BF1BB60D6DD05D30944A`

Never use:

`git add .`

Stage only exact intended files.

---

# 7. PRIMARY DEVICE / CURRENT TEST ENVIRONMENT

Primary validation device:

**Samsung Galaxy S26 Ultra, API 36, One UI**

Preferred execution mode:

`shizuku`

Normal preference:

`waitForUnlock=true`

Latest known wireless ADB endpoint from the current testing session:

`30.30.30.234:44461`

Wireless debugging ports are dynamic. Always verify with `adb devices` after reconnect/reboot rather than assuming this endpoint remains valid.

Current clean post-regression state:

- `serviceEnabled=false`;
- `ForceDozeService` stopped;
- `mForceIdle=false`;
- device ACTIVE after final unlock;
- `inDoze=false`;
- `entryPending=false`;
- `ownedReforcePending=false`;
- `sessionPhysicalMode=0`;
- no `appliedSuspendedPackages` set remains;
- `appliedSuspendedPackagesGeneration=420` remains as the generation counter;
- temporary screen timeout used for Regression 2 was restored from `15000` to `600000` ms.

Before a new ordinary Doze regression, enable EnforceDoze through its normal UI path and establish a clean unlocked baseline first.

---

# 8. IMPORTANT COMPLETED FIX HISTORY

The historical snapshots later in this file contain the full earlier chronology. Important known commits include:

## Earlier reliability work

- Phase 1 wake restoration series:
  - `5dd3ef...`
  - `dc0200...`
  - `a54be7...`
  - `5c55b03`
- Diagnostic logging:
  - `b78240449a7a993830f9f7252d3905715accf0a1`
- USER_PRESENT:
  - `e94d85036b2df9e35f43edf74b10a10bc28b6f47`
- Call recovery:
  - `cb788cf`
  - `2b09fa4`
  - `113e0651923449a15137944e32a9e818e39c4b57`
- Lockscreen sensors:
  - `fb2e9b1ff6dd3ace51ca997f067cf12b27701134`
- Package lifecycle:
  - `777ae22...`
  - `9e556fda9f5b8e39126f376ab862fb06eb51c917`
- Stats crash:
  - `65825d718f3aabfb3f421b4cedc9f274f309bee6`
- Lockscreen Doze resume series:
  - `4de3ffd...`
  - `a8fe1d0...`
  - `76861db...`
  - `2212f29...`
  - `b091745b8801d873dfeb35152f2a70a4d536a5f6`
- Fresh force/PREPARING:
  - `ea4c432...`
  - `2855424d2912095e246b47c48875ce78bb6b3e8f`
- Shizuku-unavailable handling:
  - `6b2a34c4686150879ce735f7078ccb53fd6009e4`
- Owned-session reforce:
  - `70ac1a20111cb379f64ece605ab0939f4ef3e536`
- Locked-wake physical release:
  - `a9a1227c4ff2522af345340b929e0f79325944ee`
- Late fresh force-idle settlement:
  - `dd3ded441cf1dfc9277e23ec165a99c8981ab780`
- Android 16 notification Binder fallback:
  - `a93541c14594b4fe387544aba307a22c8952fa6c`
- Notification serializer:
  - `0a2aefc0b8ffeb5acf357e4ca54c24e63d1fd7df`
- Generic device-state serializer:
  - `8555b9c9d6bd76575aeda60937e9f7d22539de47`
- Generation-safe device-state restores:
  - `d369e4e490a0cb5cd02c3eb4d5ee2fdf594ce4f8`

## Latest functional commits now on `master`

1. `78f21e0` — **Fix durable Shizuku Doze exit recovery**
2. `9b88c55` — **Fix restore retry after execution mode switch**
3. `94e70f6` — **Fix disabled boot restore after late Shizuku start**
4. `b013a48` — **Fix disabled recovery service settlement**
5. `48f7d30` — **Exclude recovery journal from Android backup**
6. `fa6fcc0` — **Fix motion-sensor teardown shell lifetime**
7. `75d2041` — **Fix failed-open root session lifecycle**
8. `ff0c2a8` — **Fix legacy pending final-exit recovery**
9. `7ced75b` — **Fix media callback completion**
10. `0bd7bd4` — **Serialize physical DeviceIdle commands**
11. `e2d914c` — **Fix durable state journal commit handling**

Latest functional baseline:

`e2d914c`

The earlier recovery commits are described in detail below.

The later Phase-0 closures, including R0-1 through R0-6, are summarized in
Section 14.

---
# 9. `78f21e0` — DURABLE SHIZUKU DOZE EXIT RECOVERY

Branch:

`fix/shizuku-recovery-leave-doze-v1`

Commit:

`78f21e0`

Subject:

`Fix durable Shizuku Doze exit recovery`

Known changed source files:

- `DozeStateStore.java`
- `ForceDozeService.java`

Recorded diff size:

- 393 insertions
- 39 deletions

This work followed the earlier suspected “logically ACTIVE but still physically forced IDLE after Shizuku failure/recovery” concern.

The earlier handoff correctly required a deterministic reproduction with real keyguard state verified rather than assuming visual unlock.

The dedicated work then established and fixed durable exit/recovery behavior.

Known physical/device validation from that work included:

- `mForceIdle` recovered `true -> false`;
- Android moved `IDLE -> ACTIVE`;
- package suspension restoration recovered after Shizuku reconnect;
- the 232-package restore debt was retained while execution was unavailable and retried rather than silently cleared;
- recovery proceeded in the same observed app PID (`21472`) in that test;
- continuation logging included:
  - `HARD_BLOCK_RESTORE_START reason=owned reforce debt settled`

Final status:

**FIXED / BUILT / DEVICE-TESTED / COMMITTED / PUSHED / MERGED**

This supersedes the old historical label:

`SUSPECTED / NEEDS DEDICATED REPRODUCTION`

for the narrow Shizuku-recovered ACTIVE physical leaveDoze durability issue that this branch addressed.

Do not infer that every possible Shizuku lifecycle edge is thereby audited; later fixes below addressed separate retry/liveness holes.

---

# 10. `9b88c55` — RESTORE RETRY AFTER EXECUTION MODE SWITCH

Branch:

`fix/shizuku-mode-switch-recovery-v1`

Commit:

`9b88c55`

Subject:

`Fix restore retry after execution mode switch`

Purpose:

Ensure unresolved restoration work is retried after an execution-backend/mode transition instead of being left stranded merely because the earlier backend attempt failed.

This is a distinct concern from:

- durable physical exit debt;
- late-Shizuku boot liveness;
- ordinary fresh-entry deferral.

Final status:

**FIXED / BUILT / DEVICE-TESTED / COMMITTED / PUSHED / MERGED**

It was the direct parent of the latest boot-liveness branch:

`94e70f6` was created from `9b88c55`.

Do not invent additional implementation specifics for this commit unless the source/diff is re-opened; the subject/status above are the authoritative facts retained from the completed session.

---

# 11. `94e70f6` — DISABLED BOOT RESTORE AFTER LATE SHIZUKU START

Branch:

`fix/boot-disabled-restore-liveness-v1`

Base:

`9b88c55`

Commit:

`94e70f6`

Subject:

`Fix disabled boot restore after late Shizuku start`

Final source diff:

```text
MyApplication.java | 50 insertions
Utils.java         | 24 insertions
2 files changed, 74 insertions(+)
```

`git diff --check` passed.

Build:

`.\gradlew.bat assembleDebug`

Result:

`BUILD SUCCESSFUL`

APK installed successfully with `adb install -r`.

## 11.1 Confirmed pre-fix liveness bug

The confirmed problematic state was:

- durable suspended-package restore debt exists;
- app is configured for Shizuku;
- `serviceEnabled=false`;
- device reboots;
- Shizuku is not yet available during the finite boot recovery attempt.

Old behavior:

1. `BootCompleteReceiver` receives boot.
2. It detects durable package/device restore debt.
3. It starts `ForceDozeService` with `ACTION_RESTORE_STATE`.
4. Shizuku is unavailable.
5. package unsuspend fails (`exit=-1`);
6. durable package record correctly remains;
7. because the EnforceDoze service is disabled, the temporary recovery service waits about 3 seconds and stops;
8. the service-level Shizuku listener disappears with `onDestroy`;
9. Shizuku starts later;
10. no remaining listener/event causes the disabled recovery service to retry;
11. packages such as ChatGPT can remain suspended indefinitely until the user manually starts/enables the app.

The journal behavior was correct; the missing piece was **liveness**.

## 11.2 Rejected design

An early candidate considered keeping the disabled recovery service alive until all debt clears.

That was rejected because if Shizuku never becomes available, a disabled foreground service could remain resident indefinitely.

The chosen fix had to remain event-driven and non-resident.

## 11.3 Relevant existing Shizuku architecture

`ShizukuHandler` already uses a global singleton and sticky binder-received listener behavior.

Shizuku provider dependency:

`dev.rikka.shizuku:provider:13.1.5`

Manifest provider is present through:

`rikka.shizuku.ShizukuProvider`

with authority based on:

`${applicationId}.shizuku`

The important behavior observed in the real test is that starting Shizuku causes Android/Shizuku to contact registered app providers, which can start the EnforceDoze process solely to deliver the binder.

That event is the natural late-recovery trigger.

## 11.4 Final implementation — `MyApplication.java`

`MyApplication` now initializes the process-level Shizuku recovery observer.

Key behavior:

- initialize `ShizukuHandler` from `Application.onCreate`;
- retain a process-level `OnAvailibilityChange` listener;
- when availability becomes `true`, call:
  - `maybeRecoverDisabledStateAfterShizukuStart()`;
- immediately call the same recovery check if `isShizukuAvailable()` is already true after listener registration, covering sticky-listener construction/order timing.

Recovery gate:

1. only if current execution mode is Shizuku;
2. return if `serviceEnabled=true` because the normal live service owns reconnect recovery;
3. inspect durable state:
   - package debt via `hasAppliedSuspendedPackages()`;
   - device-state debt via `hasPendingRestore()`;
4. if no debt, do nothing;
5. if debt exists while service is disabled, explicitly start:
   - `ForceDozeService.ACTION_RESTORE_STATE`.

Diagnostic messages added:

- `app_shizuku_restore_trigger`
- `app_shizuku_restore_start_result`

## 11.5 Final implementation — `Utils.java`

Added:

`startForceDozeServiceAction(Context context, String action)`

Purpose:

- start `ForceDozeService` for one explicit action;
- do **not** change `serviceEnabled`;
- use `startForegroundService` on Android O+;
- use `startService` on older Android;
- catch background/FGS start exceptions;
- log failure;
- return `false` rather than crash.

This is separate from existing helpers that intentionally refuse to start the normal service while `serviceEnabled=false`.

## 11.6 Files intentionally NOT changed

`ForceDozeService.java` was not modified for this fix.

The existing disabled-state `ACTION_RESTORE_STATE` behavior remains:

- issue reversion;
- if `serviceEnabled=false`, allow a short grace period;
- stop again.

This is important: the fix does not create a permanently running disabled service.

---

# 12. `94e70f6` — DECISIVE REAL-DEVICE TEST

## 12.1 Clean baseline before reproduction

Installing the patched APK while an active owned Doze session was in progress killed/replaced the running service, so a clean baseline was re-established before the decisive test.

Clean baseline eventually confirmed:

- device ACTIVE;
- `mForceIdle=false`;
- ChatGPT `suspended=false`;
- durable package debt cleared;
- `inDoze=false`;
- `entryPending=false`;
- `ownedReforcePending=false`.

## 12.2 Create fresh owned Doze

Phone was locked/screen off and allowed to enter owned Doze.

Observed:

- `mForceIdle=true`
- `mScreenOn=false`
- `mScreenLocked=true`
- `mState=IDLE`
- `mLightState=OVERRIDE`
- ChatGPT `suspended=true`
- `inDoze=true`
- package suspended-set journal present
- package generation `231`
- `ownedReforcePending=false`
- `entryPending=false`

## 12.3 Kill Shizuku during Doze

Shizuku server PID was captured using PowerShell variable:

`$shizukuPid`

because `$PID` is reserved.

Observed server PID in this test:

`16606`

After kill:

- `shizuku_server` absent.

## 12.4 Unlock while Shizuku unavailable

The phone was fully unlocked while Shizuku remained unavailable.

Observed:

- `mForceIdle=true`
- `mScreenOn=true`
- `mScreenLocked=false`
- `mState=IDLE`
- ChatGPT still `suspended=true`
- durable state:
  - `inDoze=false`
  - suspended package set still present
  - `ownedReforcePending=true`
  - package generation `231`
  - `entryPending=false`

Important logs:

- Shizuku binder died;
- package restore started for screen-on;
- batch unsuspend could not run because Shizuku unavailable;
- fallback also failed;
- `HARD_BLOCK_RESTORE_COMMAND_FINISHED exit=-1 count=232`;
- package record was kept.

This intentionally established durable unresolved debt.

## 12.5 Disable EnforceDoze while Shizuku remains unavailable

The main EnforceDoze switch was turned OFF.

Verified:

```text
serviceEnabled=false
executionMode=shizuku
```

`ForceDozeService` stopped.

Shizuku remained absent.

ChatGPT remained:

`suspended=true`

Durable package debt remained.

`ownedReforcePending=true` remained before reboot because physical unforce could not execute without the backend.

## 12.6 Reboot

Logcat was cleared and device rebooted.

Wireless ADB endpoint changed; final active endpoint became:

`30.30.30.234:38481`

The test intentionally did **not** manually open EnforceDoze or Shizuku.

## 12.7 First boot recovery attempt fails as intended

At approximately:

`20:38:39`

logs showed:

```text
Received BOOT_COMPLETED intent, isServiceEnabled=false
BOOT_RECOVERY_PENDING deviceStates=[] suspendedPackages=232
```

Boot recovery started `ACTION_RESTORE_STATE`.

The service saw:

- `pendingPackages=232`
- no device-state debt after reboot normalization

The package restore attempt failed because Shizuku was not yet available.

At:

`20:38:43.870`

observed:

`HARD_BLOCK_RESTORE_COMMAND_FINISHED exit=-1 count=232`

The durable package debt remained.

This reproduced the exact pre-fix situation up to the point where old code would have become permanently stuck.

## 12.8 Shizuku later starts and wakes EnforceDoze

Shizuku server later ran as PID:

`25450`

At:

`20:38:54.383`

Android logged:

```text
Start proc 25867:com.akylas.enforcedoze.fork/u0a707
for content provider
{com.akylas.enforcedoze.fork/rikka.shizuku.ShizukuProvider}
```

This is decisive proof that EnforceDoze was started specifically because of the Shizuku provider delivery path.

At:

`20:38:55.042`

EnforceDoze logged:

`ShizukuHandler(25867): Shizuku binder received`

At:

`20:38:55.063`

Android allowed EnforceDoze to start:

`ACTION_RESTORE_STATE`

as a background FGS with:

`code:SYSTEM_ALLOW_LISTED`

This occurred about 21 ms after the app received the binder.

## 12.9 Durable restore succeeds

The recreated recovery service saw:

- `pendingPackages=232`
- `screenOn=true`
- `inDoze=false`

At:

`20:38:55.178`

observed:

`HARD_BLOCK_BATCH unsuspend count=232 exit=0`

At:

`20:38:55.179`

observed:

`HARD_BLOCK_RESTORE_COMMAND_FINISHED exit=0 count=232`

Therefore the full 232-package restore succeeded.

## 12.10 Temporary disabled recovery service stops again

Because the app main service remained disabled:

At:

`20:38:58.162`

observed:

`Reversion issued, stopping the service again`

Then:

`Stopping service and enabling sensors`

`Service destroyed without an owned Doze session, no EXIT recorded`

At:

`20:38:59.871`

Android killed the now-empty app process.

This proves the design remains temporary/non-resident.

## 12.11 Final state

Final inspection showed:

- Shizuku running;
- no `ForceDozeService` record from the stopped recovery;
- `serviceEnabled=false`;
- `executionMode=shizuku`;
- suspended-package applied set removed;
- `ownedReforcePending=false`;
- `entryPending=false`;
- `inDoze=false`;
- ChatGPT `suspended=false`;
- `mForceIdle=false`;
- `mScreenOn=true`;
- `mScreenLocked=false`;
- `mState=ACTIVE`;
- `mLightState=ACTIVE`.

Result:

**REPRODUCED / FIXED / BUILD PASS / DEVICE TEST PASS / COMMITTED / PUSHED / MERGED TO MASTER**

---

# 13. LATEST BOOT-RECOVERY AUDIT ARTIFACTS

Protected local evidence files:

```text
boot-restore-step1.txt
boot-restore-step4.txt
boot-restore-step5.txt
boot-restore-step7.txt
boot-restore-step8-shizuku-trigger.txt
```

The final trigger log:

`boot-restore-step8-shizuku-trigger.txt`

contains roughly 7,493 lines and includes the decisive provider/binder/FGS/restore/stop sequence.

Do not commit these raw logs by default.

The GitHub continuation document should summarize evidence, while raw logs remain local unless there is a deliberate reason to publish sanitized test artifacts.

---

# 14. 2026-08-31 PHASE 0 CLOSURES AFTER `94e70f6`

Several items that were still open in the 2026-08-28 handoff are now closed.

## 14.1 Stage19 - disabled recovery settlement - `b013a48`

Confirmed pre-fix reliability bug:

- application disabled;
- durable package recovery debt exists;
- Shizuku starts temporary recovery foreground service;
- batch unsuspend fails and asynchronous per-package fallback begins;
- old fixed 3-second `stopSelf()` could destroy the service/process before fallback settled;
- callback could therefore fail to clear the durable journal.

Fix:

`b013a48 Fix disabled recovery service settlement`

The disabled recovery service now remains alive until recovery work actually settles.

The fix also prevents disabled recovery teardown from redispatching into a fresh Doze entry.

Decisive device evidence:

```text
final_unsuspend_success count=232 gen=419 durationMs=8594
disabled_restore_stop_settled ... stopped=true
disabled_restore_teardown_no_redispatch
```

Verdict:

**PASS / FIXED / COMMITTED / PUSHED / MERGED**

## 14.2 Regression 1 - duplicate recovery starts

Concern:

`MyApplication.onCreate()` and `BootCompleteReceiver` may both create recovery opportunities for the same durable debt.

Controlled testing produced:

- service recreation recovery;
- two `ACTION_RESTORE_STATE` deliveries;
- same generation-419 recovery debt;
- only one physical final unsuspend transaction.

The exact Samsung boot ordering with Shizuku already available at earliest startup was not naturally reproduced.

However, the relevant concurrency/idempotency condition was exercised and duplicate logical starts safely coalesced.

Verdict:

**PASS / NO CODE CHANGE WARRANTED**

Do not reopen this regression without new evidence.

## 14.3 Regression 2 - locked-wake same-session reforce

Normal preference:

`waitForUnlock=true`

Exact sequence tested:

```text
owned Doze
-> screen ON while still locked
-> physical forced idle released
-> logical owned session retained
-> natural screen timeout OFF while still locked
-> SAME SESSION re-forced idle
-> USER_PRESENT final exit and restore
```

Key invariants:

- logical session remained owned during locked wake;
- generation remained `420`;
- no fresh generation `421` was created;
- same session physically re-entered forced idle after locked timeout;
- final USER_PRESENT restored packages and ended the session.

Final evidence included:

```text
owned_session_resumed reason=screen off
owned_session_reforce_idle reason=screen off plan=shizuku
force_idle_attempt_start mode=resume
force_idle_result mode=resume success=true
final_unsuspend_success count=232 gen=420 durationMs=6302
```

Final durable state:

- `inDoze=false`;
- `ownedReforcePending=false`;
- `entryPending=false`;
- `sessionPhysicalMode=0`;
- no `appliedSuspendedPackages` set remained.

Verdict:

**PASS / SAME-SESSION REFORCE VERIFIED / NO CODE CHANGE WARRANTED**

## 14.4 Android backup recovery-journal safety - `48f7d30`

Release audit confirmed:

- `android:allowBackup="true"`;
- no platform backup exclusion rules;
- durable recovery journal stored in private SharedPreferences `enforcedoze_doze_state`.

That journal is valid for same-installation crash/reboot recovery but must not migrate through Android cloud backup or device transfer.

Fix:

`48f7d30 Exclude recovery journal from Android backup`

Added:

- API 23-30 `fullBackupContent` rules;
- Android 12+ `dataExtractionRules`;
- cloud-backup exclusion for `enforcedoze_doze_state.xml`;
- device-transfer exclusion for `enforcedoze_doze_state.xml`;
- ordinary user preferences remain backup-eligible.

Validation:

- XML parse PASS;
- UTF-8 no-BOM PASS;
- `git diff --check` PASS;
- debug build SUCCESS;
- merged manifest contains both backup-rule attributes.

Verdict:

**CONFIRMED RELEASE BLOCKER / FIXED / BUILT / VERIFIED / COMMITTED / PUSHED / MERGED**

Functional baseline at this checkpoint:

`48f7d30`

## 14.5 Candidate 2 - raw `setInDoze(false)` lifecycle/barrier audit

Audit target:

`DozeStateStore.setInDoze(false)` calls outside the normal transactional session-finalization path.

Current source contains only two raw `setInDoze(false)` call sites:

- `BootCompleteReceiver.recoverStateAfterBoot()`;
- `ForceDozeService.handleRestoreStateRequest()`.

The audit established that `inDoze` represents logical session ownership, while recovery debt is independently durable:

- `entryPending` / `ownedReforcePending` track physical force/unforce debt;
- `appliedSuspendedPackages` tracks package restoration debt;
- applied state keys track device-state restoration debt.

Fresh entry does not become legal merely because `inDoze=false`.

Both privileged and tunable fresh-entry paths refuse entry while package or device-state restore debt exists. Deferred Shizuku entry applies the same recovery-priority rule before retrying.

The late-Shizuku `ACTION_RESTORE_STATE` trigger in `MyApplication` is additionally restricted to:

- configured Shizuku mode;
- `serviceEnabled=false`;
- actual package or device-state recovery debt.

`ForceDozeService` is `android:exported="false"`, so another application cannot directly inject `ACTION_RESTORE_STATE`.

Boot clearing of `inDoze` is therefore intentional: reboot ends the old logical session, while any restrictions that survive reboot remain independently journalled and continue to block fresh entry until recovery settles.

Verdict:

**PASS / NO FIX**

No code change, build, APK install or device test was warranted for this source-audit candidate.

---

## 14.6 Candidate 3 - motion-sensor `onDestroy()` shell-lifetime audit

Audit target:

Motion-sensor restore/re-enable behavior when service teardown occurs while a non-Shizuku motion command is still in flight.

Source and libsuperuser analysis established a concrete lifetime violation:

- the motion serializer keeps RESTRICT in flight until its real shell callback;
- while RESTRICT is in flight, `onDestroy()` may queue ENABLE as the newest pending target;
- service teardown closes the shared `rootSession` / `nonRootSession` shells;
- the historical non-Shizuku motion path borrowed the shared `nonRootSession`;
- callback-driven pending ENABLE could therefore be dispatched after teardown had already closed that shared shell;
- libsuperuser can accept work on a closed interactive shell without physically dispatching that command.

The Shizuku motion path does not have this shared-shell teardown lifetime dependency.

Production fix:

`fa6fcc0` - `Fix motion sensor teardown shell lifetime`

Non-Shizuku motion commands now receive a dedicated `Shell.Interactive`:

- `.useSH()` preserves the historical non-root backend;
- five-second watchdog;
- minimal logging;
- `.setDetectOpen(false)`;
- zero-argument `.open()`;
- real exit code/stdout/stderr are forwarded unchanged;
- `closeWhenIdle()` performs non-blocking cleanup after the real callback;
- accepted/in-flight commands are not artificially completed;
- the existing latest-wins motion serializer remains unchanged;
- Shizuku continues through the existing `executeCommand()` path.

Deterministic teardown-race validation:

A temporary test-only hook physically applied RESTRICT and then held real command completion for 15 seconds. During that interval the service was stopped through the normal Android service lifecycle.

Observed ordering:

```text
12:53:55.039  C3_RESTRICT_APPLIED rc=0
12:53:55.244  APP | onDestroy
12:54:10.052  MOTION | restrict label=motion_enter exit=0
12:54:10.102  MOTION | enable label=motion_final_restore exit=0
```

The callback-driven ENABLE therefore survived service teardown and executed only after the older RESTRICT reached its real callback.

Android recreated the service during the test and performed an additional early durable motion restore. That did not invalidate the lifetime proof: the original held RESTRICT still completed later and its serializer continuation successfully dispatched ENABLE.

Final SensorService state:

`Mode : NORMAL`

Additional validation:

- all temporary Candidate 3 race instrumentation was removed;
- the temporary `rootSession` close guard was removed;
- `git diff --check` PASS;
- clean debug build PASS;
- production APK installed successfully;
- clean APK SHA-256: `46A8089F7F70956B55D355927D78AFAD88DE11095F2D1CA6D88F839BDDAA3174`;
- the post-test clean APK was byte-for-byte identical to the pre-race production candidate;
- normal device configuration was restored.

Final normal configuration:

```text
serviceEnabled=true
executionMode=shizuku
disableMotionSensors=false
turnOffAllSensorsInDoze=true
SensorService=Mode : NORMAL
device=ACTIVE
```

Separate finding:

A failed-open libsuperuser `rootSession` can remain non-null and later throw `NullPointerException` from `Shell.Interactive.close()` during `onDestroy()`.

That issue is real but is not part of Candidate 3. The temporary guard used during deterministic testing was removed before the production build and commit.

Verdict:

**CONFIRMED RELEASE BLOCKER / FIXED / BUILT / DEVICE-VERIFIED / COMMITTED / PUSHED / MERGED / MASTER PUSHED**

Candidate 3 was pushed and merged successfully.

Functional commit: `fa6fcc0`

Documentation commit: `50221ed`

Master merge commit / current functional baseline: `e4bb9ca`

---

## 14.7 Candidate 4 - failed-open `rootSession` lifecycle / teardown safety

Audit target:

`ForceDozeService.rootSession` publication and teardown safety when libsuperuser
`.useSU().open(...)` fails before the interactive shell is fully initialized.

Candidate 3 testing had independently exposed a real crash:

```text
java.lang.RuntimeException: Unable to stop service ...
Caused by: java.lang.NullPointerException
    at eu.chainfire.libsuperuser.Shell$Interactive.closeImmediately(...)
    at eu.chainfire.libsuperuser.Shell$Interactive.close(...)
    at com.akylas.enforcedoze.ForceDozeService.onDestroy(...)
```

Root cause analysis of `eu.chainfire:libsuperuser:1.1.0.201907261845` established:

- `Shell.Builder.open(listener)` may return a partially initialized
  `Shell.Interactive`;
- an early low-level open failure reports reason `-3`;
- because `rootShellExecutor` has no Looper/Handler, that `-3` callback can run
  synchronously before `.open(listener)` returns;
- the historical code assigned the returned object directly to static
  `rootSession`;
- `rootSession != null` therefore did not prove that the interactive shell was
  safe to close;
- `Shell.Interactive.close()` delegates to `closeImmediately()`;
- `closeImmediately()` assumes process/stdin/stdout/stderr were initialized and
  can throw `NullPointerException` for an early failed-open object.

A simple `onDestroy()` exception guard was rejected as the production fix because
it would only mask the invalid lifecycle state.

`waitForOpened(null)` was also rejected. A low-level initialization failure can
occur after a process object exists but before libsuperuser marks the shell
running; waiting for the open state in that condition can block indefinitely.

Production candidate invariant:

**Do not publish an early failed-open `Shell.Interactive` into static
`rootSession`.**

All three `ForceDozeService` root-open paths now use the same non-blocking
callback handshake:

```text
rootShellExecutor task
-> reuse rootSession only when non-null and isRunning()
-> otherwise clear rootSession
-> create local candidate with open callback
-> callback records the open result and queues continuation on rootShellExecutor
-> publish candidate only while result is unknown or SHELL_RUNNING
-> synchronous early failure (-3) is therefore never published
-> asynchronous failure clears rootSession only when it still references that
   exact candidate
-> success dispatches the command on that exact opened candidate
```

The identity check prevents a delayed failure callback from clearing a newer
replacement shell.

Scope remained intentionally narrow:

- only `ForceDozeService.rootSession` lifecycle was changed;
- Shizuku command behavior was preserved;
- Candidate 3 motion-shell behavior was preserved;
- `nonRootSession` was not changed;
- Activity-local shell implementations were not changed;
- executor shutdown/lifecycle redesign was not added.

Source validation:

- branch: `fix/root-session-failed-open-v1`;
- only tracked production file modified:
  `app/src/main/java/com/akylas/enforcedoze/ForceDozeService.java`;
- all three direct historical `rootSession = new Shell.Builder...` publication
  patterns were replaced by the callback handshake;
- no `waitForOpened` remains in `ForceDozeService`;
- `git diff --check` PASS;
- final production-candidate diff:
  `149 insertions(+), 50 deletions(-)`;
- final source diff is identical to the protected pre-runtime audit snapshot
  `candidate4-current-review.diff`.

Candidate 3 regression protection:

The motion helper remains:

```text
Shizuku -> existing executeCommand path
non-Shizuku -> dedicated useSH() Interactive shell
watchdog -> 5 seconds
detectOpen -> false
real callback -> preserved
cleanup -> closeWhenIdle()
```

Clean build:

```text
BUILD SUCCESSFUL
38 actionable tasks: 38 executed
```

Production-candidate debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
SHA-256:
6BF751F1E885B3CD3D414F6B8DC2F37FFD7912C015A89D0D0ED6679AA34549D6
```

Normal Shizuku smoke test passed before root-failure testing.

Deterministic failed-open validation used the API 36 test device with
`executionMode=root` while `su` was genuinely unavailable:

```text
/system/bin/sh: su: inaccessible or not found
```

The target early failure was reached repeatedly:

```text
Error opening root shell: exitCode -3
```

Multiple later commands in the same app process produced fresh `-3` open
failures, proving the poisoned failed-open candidate was not retained/reused as
`rootSession`.

Normal service teardown was then invoked through the app UID:

```text
run-as com.akylas.enforcedoze.fork
/system/bin/am stopservice --user 0
-n com.akylas.enforcedoze.fork/com.akylas.enforcedoze.ForceDozeService
```

Android reported:

```text
Service stopped
```

The service entered its destruction path:

```text
Stopping service and enabling sensors
HARD_BLOCK_RESTORE_START reason=service destroyed count=232
```

Teardown itself continued to issue root commands and received controlled
`exitCode -3` failures.

Critically, the teardown evidence contained no:

```text
Unable to stop service
closeImmediately
FATAL EXCEPTION
```

`dumpsys activity services` confirmed `ForceDozeService` was gone after the
normal stop, while the application process remained alive. This proves service
teardown completed rather than being hidden by whole-process termination.

Final normal configuration was restored:

```text
serviceEnabled=true
executionMode=shizuku
disableMotionSensors=false
turnOffAllSensorsInDoze=true
SensorService=Mode : NORMAL
mForceIdle=false
mScreenOn=true
mScreenLocked=false
mState=ACTIVE
mLightState=ACTIVE
ForceDozeService running
```

Separate follow-up:

`nonRootSession` has structurally similar lifecycle questions, but no equivalent
runtime failure was proven during Candidate 4. It remains outside this fix and
must not be changed retrospectively without separate evidence.

Verdict:

**CONFIRMED RELEASE BLOCKER / FIXED / BUILT / DEVICE-VERIFIED**

Functional commit: `75d2041`
---

## 14.8 R0-1 - dead / unreachable `leaveDoze`

Result:

**PASS / NO CHANGE**

The suspected dead or unreachable `leaveDoze` behavior was audited from current
source and did not justify a production change.

Do not reopen without new evidence.

## 14.9 R0-3 - legacy ACTIVE + UNKNOWN final-exit ownership

Result:

**CONFIRMED / FIXED / BUILT / DEVICE-VALIDATED / MERGED / PUSHED**

Branch:

`fix/legacy-final-exit-pending-v1`

Functional commit:

`ff0c2a8`

Merge:

`cc83c7d`

The fix preserves a durable pending final-exit obligation when legacy state
cannot prove that physical DeviceIdle release completed.

Validated APK SHA256:

`A2DC07BEF39824952C26644E2B8FF0B7A21E110A8828261611D36AD9EC9D84AB`

## 14.10 R0-4 - media callback completion

Result:

**CONFIRMED / FIXED / BUILT / DEVICE-VALIDATED / MERGED / PUSHED**

Branch:

`fix/media-callback-completion-v1`

Functional commit:

`7ced75b`

Merge:

`51209ab`

`NotificationService.getPlayingPackageName()` now has an exactly-once
completion gate so callback absence cannot leave the caller unresolved.
No-media fallback and disconnect behavior were validated.

Validated APK SHA256:

`D6E36CBA3F291B6A3FAA8026295C0CF5FF37861AAB8349E5477D3B1D74131B6B`

## 14.11 R0-5 - root child/orphan physical DeviceIdle serialization

Result:

**CONFIRMED / FIXED / BUILT / DEVICE-VALIDATED / MERGED / PUSHED**

Branch:

`fix/root-physical-serialization-v1`

Functional commit:

`0bd7bd45da079415cb8739bb231f17efa42cfe23`

Merge:

`2297eca0feacc23c1df4149796c254c2048e1b63`

The confirmed defect was that a native/root child could outlive the app process
and race durable recovery, potentially leaving markerless forced DeviceIdle.

All physical:

```text
dumpsys deviceidle force-idle deep
dumpsys deviceidle unforce
```

operations are now serialized across app processes and root/Shizuku using the
Toybox advisory lock:

`/data/local/tmp/com.akylas.enforcedoze.fork-deviceidle.lock`

Validated APK SHA256:

`9525C4341E3F5444E08E21AA45E8441A65C5093963EA85FADBA0E1FEFB211DA9`

## 14.12 R0-6 - durable SharedPreferences state-journal commit handling

Result:

**CONFIRMED / FIXED / BUILD PASS / M30 RUNTIME VALIDATED / FUNCTIONALLY COMMITTED**

Branch:

`fix/durable-state-journal-commit-v1`

Functional commit:

`e2d914c0f2eaa44e3f3450f83849aae8d5fcedc5`

R0-6 was subsequently fast-forward merged to local `master`. Push status is
separately approval-controlled and must be verified from the live Git refs.

Confirmed defect classes:

- `markApplied()` ignored the synchronous SharedPreferences commit result even
  though durable ownership must exist before a physical state change;
- `beginSuspendedPackageSession()` could allow physical package suspension
  without durable package ownership;
- `clearAppliedIfGeneration()` could report successful restore while failing
  to durably clear recovery debt;
- `clearAppliedSuspendedPackagesIfGeneration()` had the same conservative-clear
  problem for package ownership.

Production behavior now:

- physical state changes are dispatched only after `markApplied()` durably
  commits ownership;
- package suspension is dispatched only after the package session is durably
  committed;
- failed commit attempts restore the exact previous in-process
  SharedPreferences view;
- generation-aware clear methods return success only after the durable clear
  succeeds;
- failed clears conservatively retain recovery debt.

Two production files changed:

```text
app/src/main/java/com/akylas/enforcedoze/DozeStateStore.java
app/src/main/java/com/akylas/enforcedoze/ForceDozeService.java
```

Functional diff:

```text
2 files changed, 205 insertions(+), 53 deletions(-)
```

Final runtime-validated diff SHA256:

`289FCE18ADDEBA5C129D2ED7BBD968D15680A363436505E0F0542D45297DABD8`

Validated Candidate 1 APK SHA256:

`237F3DD236071CC60393A6122173FC2077B82501F5C5B76D6358FFE52C3496D8`

Dynamically proven on the M30:

- exact installed APK identity;
- normal `markApplied()` success;
- durable journal before physical motion restriction;
- normal Doze entry and restore;
- generation-aware motion clear;
- a genuine filesystem-induced SharedPreferences `commit()==false`;
- conservative ownership retention while persistence was unavailable;
- successful convergence after persistence became writable again;
- durable package journal before Spotify suspension;
- Spotify suspension and unsuspension;
- generation-aware package journal clear;
- final neutral M30 restoration.

The exact new Candidate-1 failure sub-branches were **not individually forced**
at runtime:

- exact `markApplied()` commit-failure branch;
- exact `clearAppliedIfGeneration()` commit-failure branch;
- exact `beginSuspendedPackageSession()` commit-failure branch;
- exact suspended-package-clear commit-failure branch.

Those paths are structurally reviewed and statically gated. Do not claim that
they were individually hit dynamically, and do not add unsafe test hooks or
increasingly invasive filesystem manipulation merely to force them.

Important deferred durability item:

`DozeStateStore.setInDoze(boolean)` still ignores its synchronous commit result.

The earlier raw `setInDoze(false)` lifecycle/barrier audit remains valid for the
narrow question it examined, but the newly identified persistence/durability
question is separate and remains deferred for a focused Phase-0 audit.

Final M30 package-test restoration:

```text
dozeAppBlockList = explicit empty set
com.spotify.music installed=true
com.spotify.music suspended=false
```

The pre-test absence of the blocklist key and the current explicit empty set are
functionally equivalent for the current string-set loader. The live
SharedPreferences XML was therefore not overwritten merely to remove the empty
set.

---

## 14.13 Maintenance async restore/reapply behavior

Result:

**CONFIRMED / FIXED / BUILD PASS / M30 RUNTIME VALIDATED / DOCUMENTED /
MERGED / MASTER PUSHED**

Functional branch:

`fix/maintenance-async-reapply-v1`

Functional commit:

`434ce6508764bdcf2b34221f85858f9c0ce1a440`

Documentation commit:

`fbc41b67b8e17dcc47a6bab93ecc818a34430e6d`

Current integrated master:

`fbc41b67b8e17dcc47a6bab93ecc818a34430e6d`

Production scope:

`app/src/main/java/com/akylas/enforcedoze/ForceDozeService.java`

No `DozeStateStore` production change was required.

### Confirmed defect

Maintenance restoration is asynchronous. Six generic Doze reapply predicates
could read the still-old physical Doze target while maintenance restoration was
outstanding:

- airplane mode;
- Bluetooth;
- GPS;
- Wi-Fi;
- mobile data;
- Battery Saver.

That could cause maintenance exit to skip the new journal/reapply request
entirely.

`ALL_SENSORS` is maintenance-restored but is not the exact predicate defect
because its entry behavior is preference-driven.

### Candidate 5 design

The final implementation preserves maintenance intent independently of transient
physical state and protects stale callback/cycle boundaries:

- exact maintenance-cycle identity;
- latest-wins network-entry token;
- exact maintenance snapshots including captured `previousValue`;
- maintenance reapply predicates use captured expected pre-state;
- stale async music callbacks are rejected by session/entry/cycle checks;
- maintenance lifecycle invalidation clears stale context;
- maintenance exit invalidates the previous network-entry token while still
  under `physicalEntryLock`;
- `stateRestoreInFlight` tracks exact `(key, generation)` identities rather than
  only a key or only the newest generation.

### Static/build identity

Final source SHA256:

`41B99ED3C15B0FB7605081D67A63259AA537D08D968CBFF47EF84FDA1211ADF7`

Candidate-5 prebuild/final-runtime/staged diff SHA256:

`A3B351AA948998F5B05D9596B87B7C85702DD4735E806CF7284CA49B9769C6E3`

Frozen/installed Candidate-5 debug APK SHA256:

`3585AF7EA96B071F3BBFC345066F7B6FF369E516FE73F564E11B668B2D52A0CD`

### M30 runtime validation

Battery Saver was used as the representative generic key.

The defining asynchronous overlap was deliberately exercised with a rapid:

```text
IDLE -> IDLE_MAINTENANCE -> IDLE
```

cycle.

Observed ordering proved that deep idle returned while the old restore was still
outstanding, generation 4 was still journaled/re-applied, the old maintenance
restore physically turned Battery Saver OFF, and the newer Doze reapply then
turned it ON.

Final cleanup passed:

```text
EnforceDoze process = stopped
turnOnBatterySaverInDoze=false
mForceIdle=false
Battery Saver=false
inDoze=false
sessionPhysicalMode=0
entryPending=false
ownedReforcePending=false
finalExitPending=false
gen.motionSensors=22
gen.batterySaver=9
no applied.batterySaver
no applied.motionSensors
```

### Evidence boundary

The exact case of two different restore generations for the same key both
simultaneously outstanding in the restore tracker was not dynamically forced.
That specific tracker property remains static-review covered.

The stale async music-callback/token protections were also structurally reviewed
rather than separately forced as a dedicated runtime case.

Do not exaggerate those narrower evidence boundaries.

Maintenance process-death behavior was subsequently audited separately and is now
functionally fixed by `d80a5ee`; see the following section.

---

## 14.14 Maintenance process-death recovery — `d80a5ee`

Result:

**CONFIRMED / FIXED / BUILD PASS / M30 RUNTIME VALIDATED / FUNCTIONALLY COMMITTED**

Branch: `fix/maintenance-process-death-v1`

Functional commit: `d80a5ee` — `Fix maintenance process-death recovery`

Production scope:

- `app/src/main/java/com/akylas/enforcedoze/DozeStateStore.java`
- `app/src/main/java/com/akylas/enforcedoze/ForceDozeService.java`

Functional diff:

```text
2 files changed
1456 insertions
65 deletions
```

Confirmed defect: maintenance state was partly process-local. During an owned Doze
maintenance window, generic restrictions are temporarily restored. If the service
process died after that temporary restore but before maintenance exit, the recreated
process could lose the maintenance context and fail to reapply the restriction when
deep IDLE resumed.

Final design:

- durable maintenance-active marker and monotonically increasing generation;
- exact generic maintenance reapply key set;
- ordinary per-key applied owner/generation remains authoritative;
- successful temporary restore preserves the ordinary owner;
- recovered keys are reconstructed only from exact durable ownership;
- missing ownership is never guessed from physical state;
- exact-generation settlement prevents stale callbacks retiring newer debt;
- recovered single-flight prevents duplicate reapply scheduling;
- process recreation during an open maintenance window reconstructs the maintenance
  barrier and does not prematurely force deep idle;
- process recreation after maintenance already ended can immediately resume durable
  reapply work;
- service/session invalidation clears recovered RAM ownership.

Generic maintenance-reapply coverage: airplane mode, Bluetooth, GPS/location, Wi-Fi,
mobile data, and Battery Saver. `ALL_SENSORS` remains outside this generic set.

Build identity:

```text
build log SHA256 = 58ACBCF0ABAD7F14E18858C27E3CDB6439C66363F7ADECFCA4192D2008D32751
APK SHA256       = 6F00EF54CE153EA3BFD62B23773AFD42CB3DD6EF7E68D30A2DFF5C68FAC18D94
final diff SHA256= A3A4EF5928BD1EAC92707F104AA908520AFB3084D64E61AD8486316B476F3F44
```

M30 runtime validation reproduced process death inside `IDLE_MAINTENANCE`, preserved
the recovered maintenance window, then observed real maintenance exit followed by:

```text
Enabling Battery Saver
MAINTENANCE_REAPPLY_SETTLED batterySaver maintenanceGen=1
```

Final M30 cleanup:

```text
mForceIdle=false
mScreenOn=true
mState=ACTIVE
low_power=0
```

Git state at this checkpoint:

```text
HEAD / feature branch = d80a5ee
master                = d7c214d
origin/master         = d7c214d
```

`d80a5ee` is functionally committed only. Documentation, merge, feature push, and
master push remain separate approval-gated actions.

Protected evidence additionally includes all `maintenance-process-death-*` files.
Do not stage those evidence files by default.

---

# 15. SEPARATE NOTIFICATION BOOT-RECOVERY OBSERVATION

During the boot-liveness source audit, notification restoration was examined.

Important distinction:

- package/device restore debt has durable journal support;
- notification operations are serialized in memory;
- notification-only restore debt does not appear to use the same durable boot journal mechanism.

No notification-only boot-loss bug was proven during `94e70f6`.

Do not expand the boot package/device liveness fix retrospectively.

Keep this as a separate future audit item if notification-only crash/boot recovery needs deterministic review.

---

# 16. HISTORICAL DEEP DETAILS THAT MUST REMAIN KNOWN-GOOD

## Late fresh force-idle settlement — `dd3ded4`

A real device showed that the shell command could report:

`Now forced in to deep idle mode`

while an immediate `PowerManager.isDeviceIdleMode()` sample was still false.

The old implementation incorrectly classified this as semantic refusal and cleared PREPARING, while the deep-idle broadcast arrived about 47 ms later, producing an orphan physical force:

- logical/durable state false;
- `mForceIdle=true`;
- Android IDLE.

The fix uses a two-signal settlement model:

- exact entry attempt token;
- command acceptance signal;
- physical deep-idle observation signal;
- commit only when both belong to the same current attempt;
- no timer;
- no polling.

Do not claim the stochastic `accepted_pending_confirmation` timing was naturally re-hit after the fix unless new evidence does so.

The full historical source snapshot later in this document retains the detailed verdict ordering, lock invariant, cancellation behavior, and test plan.

## Locked-wake physical release — `a9a1227`

With `waitForUnlock=true`:

- lockscreen wake physically unforces Android;
- same logical owned session remains;
- same epoch/generation remains;
- no EXIT on mere locked wake;
- screen-off while still locked should reforce the same owned session;
- USER_PRESENT performs final restore/unforce/EXIT.

Historical evidence already validated locked-wake release and final unlock behavior.

One older planned test — lockscreen visible, screen times out OFF while still locked, same-session genuine owned reforce — was explicitly noted as not directly completed at that checkpoint. Unless later source/device evidence is found, keep it on the regression backlog rather than silently marking it done.

## Generic state serialization

Generic async physical toggles are latest-wins serialized per state.

Includes:

- mobile data;
- Wi-Fi;
- battery saver;
- airplane mode;
- Bluetooth;
- GPS.

Do not assume dispatch order equals completion order.

## Generation-safe restore — `d369e4e`

Durable device-state restore markers use generations so stale callbacks cannot erase newer debt.

Important concepts:

- generation stored per applied key;
- `AppliedKeySnapshot`;
- `clearAppliedIfGeneration`;
- selection/snapshot/dispatch protected by `physicalEntryLock`;
- fresh-entry debt barrier prevents a new session from capturing still-restricted state as its new pre-Doze baseline;
- maintenance reapply is not treated as a fresh session.

The exact old-generation/new-generation callback overlap was not naturally reproduced; do not exaggerate evidence.

---

# 17. FORK FEATURES / ARCHITECTURE TO PRESERVE

Preserve unless a concrete audit proves a defect:

## Dual-install/fork identity

- namespace base;
- `.fork` application ID;
- manifest `${applicationId}`;
- FGS specialUse setup;
- shell grants/whitelist based on dynamic package ID;
- explicit `.fork` preferences.

## Settings persistence

- SettingsActivity reload notifies service;
- execution mode committed before reload;
- multiple Shizuku listeners supported;
- destructive preference writes removed.

## `SettingsBackup.java`

- SAF Create/Open;
- background import/export;
- one reload after import.

## Multi-select package chooser

- search;
- system/user filter;
- select all;
- batch result;
- background labels;
- lazy icons;
- BlockApps batch commits once.

## `DozeStateStore`

Durable private preferences:

`enforcedoze_doze_state`

Tracks relevant:

- pre/applied physical states;
- `inDoze`;
- synchronous durable writes;
- package generation/session ownership;
- `entryPending`;
- `ownedReforcePending`;
- generation-safe device-state restore markers.

## `BootCompleteReceiver`

Recovery covers relevant:

- device-state pending;
- package-only pending;
- fresh PREPARING recovery;
- owned-reforce recovery;
- package/device restore request after reboot.

## `ShizukuHandler`

Important architecture:

- binder listeners;
- multiple availability listeners;
- command execution on Java threads/processes;
- Shizuku UserService command backend via `IShizukuCommandService` AIDL;
- non-daemon UserService lifecycle so Shizuku tears down the privileged service when the binding app process dies;
- each privileged command runs in an isolated `setsid` process group so teardown kills descendants as well as the tracked shell;
- stdout and stderr are drained concurrently to avoid pipe deadlock;
- sticky binder delivery behavior used by the new application-level recovery.

---

# 18. CURRENT COMPLETED / OPEN AUDIT MATRIX

## Completed / no fix

- handleScreenOn/session-exit barrier — PASS / NO FIX
- sensor serializer narrow concurrency — PASS / NO FIX
- duplicate recovery-start coalescing after `94e70f6` — PASS / NO FIX
- exact same-session lockscreen timeout reforce — PASS / NO FIX
- earlier raw `DozeStateStore.setInDoze(false)` lifecycle/barrier audit —
  PASS / NO FIX for that narrow lifecycle question
- R0-1 dead or unreachable `leaveDoze` behavior — PASS / NO CHANGE

## Completed / fixed

- notification shell disable/enable ordering
- generic device-state command ordering
- generic device-state generation races
- late fresh force-idle settlement
- locked-wake physical release
- Shizuku durable physical exit recovery
- restore retry after execution-mode switch
- disabled boot restore after late Shizuku start
- disabled recovery service settlement — `b013a48`
- Android backup/device-transfer exclusion for durable recovery journal —
  `48f7d30`
- Candidate 3 motion-sensor `onDestroy()` shell lifetime — `fa6fcc0`
- Candidate 4 failed-open `rootSession` lifecycle / teardown safety —
  `75d2041`
- R0-3 legacy final-exit ownership — `ff0c2a8`, merged by `cc83c7d`
- R0-4 media callback completion — `7ced75b`, merged by `51209ab`
- R0-5 root child/orphan DeviceIdle serialization — `0bd7bd4`, merged by
  `2297eca`
- R0-6 durable SharedPreferences state-journal commit handling — `e2d914c`,
  fully closed and merged/pushed
- maintenance async restore/reapply behavior — functional `434ce65`,
  documentation/master `fbc41b6`; M30 async-overlap runtime PASS; merged and
  pushed to `master`

## Checkpoint A - Shizuku UserService modernization

Branch:

`fix/shizuku-userservice-v1`

Functional commit:

`58c726714d17b40c5cb18a48cc67aec82cff7998`

Subject:

`Migrate Shizuku backend to UserService`

Production scope:

- `app/build.gradle`
- `app/src/main/aidl/com/akylas/enforcedoze/IShizukuCommandService.aidl`
- `app/src/main/java/com/akylas/enforcedoze/ShizukuCommandService.java`
- `app/src/main/java/com/akylas/enforcedoze/ShizukuHandler.java`

What changed:

- deprecated `Shizuku.newProcess()` / `ShizukuRemoteProcess` execution was removed from the migrated backend;
- commands now execute through a Shizuku non-daemon UserService and AIDL Binder interface;
- stdout and stderr are drained concurrently;
- command retry semantics remain unchanged;
- Root mode was intentionally left unchanged;
- the final Candidate 2 runs each privileged command in its own `setsid` process group and kills the whole process group during UserService teardown.

Important Candidate 1 finding:

- Candidate 1 correctly killed the UserService and tracked command shell on app-process death;
- a spawned `sleep` descendant survived because Java `Process.destroy()` only killed the tracked shell;
- Candidate 1 was therefore rejected and never committed.

Candidate 2 validation:

- build PASS;
- normal Shizuku command PASS;
- real force-idle path PASS;
- Battery Saver / motion privileged command path PASS;
- app-process death -> old UserService destruction PASS;
- app restart -> fresh UserService PASS;
- UserService-only death -> reconnect with same app process PASS;
- active-child orphan test PASS:
  - old UserService gone;
  - old command shell gone;
  - spawned `sleep` child gone;
  - log confirmed `Killed command process group ... reason=destroy`;
- stdout/stderr regression PASS with `1000` stdout lines and `1000` stderr lines through the final process-group launcher.

Validated Candidate 2 production APK:

`shizuku-userservice-candidate2-debug.apk`

SHA-256:

`C0D2E3366DEB7897BD7F5A30A433A22E50B0FE2B81EBFF78E29FC49BB5077614`

Final production review:

`shizuku-userservice-candidate2-final-review.txt`

SHA-256:

`17B6A428264705DB173200D1777A3AAD8E05D6310F370450696EA3BCCF3D101F`

Final status at this documentation update:

**FIXED / BUILT / M30 DEVICE-TESTED / FINAL-REVIEWED / FUNCTIONAL COMMIT CREATED**

Feature push, documentation commit, merge to `master`, and master push remain separate approval-controlled actions.

## Follow-up / deferred

Phase 0 is **not yet declared fully complete**.

The maintenance async restore/reapply item is fully closed for its audited scope.

Remaining public-beta runtime/state-integrity backlog:

1. notification blocklist exact-set durable ownership;
2. notification-only process-death / boot restoration;
3. biometric real pre-state correctness;
4. focused `setInDoze(false)` durability/lifecycle;
5. public-beta minimum Android decision: raise `minSdkVersion 23` to `24`.

These five items are the planned **Checkpoint B - public-beta state integrity** bundle.
Create it from the validated/integrated Checkpoint A baseline on:

`fix/public-beta-state-integrity-v1`

Checkpoint B should consolidate the correction, then perform one review -> build -> M30 synthetic validation -> S26 normal-use validation -> freeze.

Later/deferred items after Checkpoint B unless new evidence raises their priority:

- tunable callback absence;
- marker-stuck recovery;
- PREPARING phantom boot / stale-session behavior.

Closed and removed from the backlog:

- maintenance process-death recovery;
- Shizuku `newProcess` deprecation/newer-Android backend risk;
- stdout/stderr pipe deadlock risk.

The focused `setInDoze(false)` durability item remains separate from the earlier
narrow lifecycle/barrier PASS / NO FIX audit.

Do not reopen closed work without new evidence.
Do not invent a new R0 number without first checking the tracked roadmap and current documentation.

---

# 19. TESTING RULES

Always verify real state.

## Display

Do not trust a visually black Samsung screen.

Use system dumps.

## Keyguard

Do not infer unlock from appearance.

Verify keyguard state.

## Device idle

Track at minimum:

- `mForceIdle`
- `mScreenOn`
- `mScreenLocked`
- `mState`
- `mLightState`

## Durable journal

Inspect:

- `inDoze`
- `entryPending`
- `ownedReforcePending`
- applied package set
- generations
- pending restore keys

## Package suspension

Use a representative package such as ChatGPT only as a test indicator; package journal count/logs are the more complete proof.

## Backend recovery

When killing Shizuku, explicitly verify `shizuku_server` is absent before interpreting failures.

When it returns, distinguish:

- binder availability;
- app process wake;
- recovery-service start;
- actual shell success;
- durable marker clear;
- final service stop.

---

# 20. BUILD ENVIRONMENT NOTE

An older Windows checkpoint had Gradle accidentally using a 32-bit Java 8 runtime and failing before compilation with heap reservation error.

The working solution was to use Android Studio's 64-bit JBR for the PowerShell session when needed:

`C:\Program Files\Android\Android Studio\jbr`

Do not treat the old 32-bit Java failure as a source-code failure.

Current latest patched tree built successfully.

Do not make global Java changes unless necessary.

---

# 21. GIT WORKFLOW FOR FUTURE FUNCTIONAL FIXES

For each narrow fix:

1. verify `master`, `origin/master`, and worktree;
2. create a narrow feature branch;
3. perform read-only source audit;
4. implement only after defect/mechanism is understood;
5. independently inspect diff;
6. `git diff --check`;
7. build;
8. install;
9. establish clean device baseline;
10. perform relevant deterministic test;
11. restore temporary settings;
12. run regression;
13. inspect `git status`;
14. stage only intended files;
15. `git diff --cached --check`;
16. `git diff --cached --stat`;
17. wait for **approve commit**;
18. commit;
19. verify HEAD/status;
20. wait for **approve push**;
21. push feature branch;
22. verify;
23. wait for **approve merge to master**;
24. fast-forward merge when possible;
25. wait for **approve push master**;
26. push master;
27. verify `master == origin/master`.

Never stage protected audit artifacts accidentally.

---

# 22. NEW GITHUB CONTINUITY WORKFLOW

From this point onward, maintain a tracked repository document:

`PROJECT_CONTINUATION.md`

This file should become the project's human/AI handoff record.

## Goal

If a ChatGPT window is lost or becomes too long:

1. open a new chat;
2. attach the latest `PROJECT_CONTINUATION.md` from GitHub/local repo;
3. tell ChatGPT to follow the **FINAL CONTINUATION PROMPT**;
4. continue from the exact last verified state.

## What to update after each completed fix

Update the top authoritative sections with:

- date;
- current `master` and `origin/master` SHA;
- branch/commit subject;
- exact files changed;
- bug status;
- source-review status;
- build status;
- device-test evidence;
- what was naturally reproduced versus structurally proven;
- current user/device settings that matter;
- protected untracked artifacts;
- next audit item;
- updated final continuation prompt.

## Recommended documentation-commit discipline

Do not mix documentation into an unreviewed functional change, but documentation
does not need to wait for a separate historical docs-only branch.

Current controlled sequence:

1. complete final technical validation;
2. obtain explicit **approve commit** for the functional change;
3. stage only the exact production files and review the staged diff;
4. create the functional commit;
5. inventory and update the authoritative repository documentation;
6. review the documentation diff;
7. obtain a separate explicit **approve commit** for documentation;
8. stage only `PROJECT_CONTINUATION.md`;
9. create the documentation commit;
10. obtain explicit **approve merge to master** before merging;
11. obtain explicit **approve push** / **approve push master** before each
    applicable push.

Functional commit, documentation commit, feature push, merge, and master push
remain separate approval gates.

The historical `docs/continuation-20260828` branch may remain for provenance but
is not automatically the destination for new continuity updates.

## Raw logs

Do not make GitHub a dump of raw logcat files by default.

Prefer:

- concise evidence summaries in `PROJECT_CONTINUATION.md`;
- commit hashes;
- exact key log lines;
- reproducible test procedures.

Keep raw logs local unless sanitized publication has a concrete purpose.

---

# 23. PUBLIC RELEASE PLAN — LATER

Only after reliability auditing is frozen:

1. full regression suite;
2. review all integrated fixes;
3. complete rebranding;
4. app/fork identity review;
5. new icon/adaptive icon/splash/artwork;
6. UI/settings modernization;
7. donation/support review;
8. preserve upstream attribution and legal notices;
9. professional GitHub README;
10. screenshots;
11. installation/build instructions;
12. Shizuku instructions;
13. root instructions if retained;
14. supported Android/Samsung notes;
15. troubleshooting;
16. changelog;
17. audit/test history;
18. architecture/reliability notes;
19. known limitations;
20. credits/upstream links;
21. release notes;
22. signing/release process;
23. GitHub Releases/APK publication.

Do not mix this work into current reliability branches.

---

# PART II — HISTORICAL SOURCE SNAPSHOT A

The following is the earlier master handoff retained for lossless historical reference.

**It is historical. PART I supersedes any stale “current state,” ADB endpoint, master SHA, branch status, or next-step statement inside this quoted snapshot.**

> # EnforceDoze Fork — Master Continuation Prompt
>
> I am continuing a long-running audit, hardening, modernization, device-testing, and eventual public-release project for an Android app fork based on **EnforceDoze**.
>
> Treat everything below as the authoritative project state unless I explicitly provide newer evidence.
>
> ---
>
> # 1. PROJECT GOAL
>
> The project is not just to patch individual bugs.
>
> The overall plan is:
>
> ## Phase A — Complete reliability/security/concurrency audit
> Systematically inspect and fix correctness problems in:
>
> - Doze entry
> - Doze exit
> - screen on/off handling
> - unlock handling
> - Shizuku execution
> - root execution
> - package suspension/restoration
> - notification disable/restore
> - Wi-Fi/mobile-data/Bluetooth/GPS/airplane/battery-saver state handling
> - sensor state handling
> - biometric state handling
> - maintenance-window handling
> - async shell command ordering
> - process death/recovery
> - boot/restart recovery
> - durable state journals
> - race conditions
> - stale callbacks
> - generation/session ownership
> - root/Shizuku backend failures
> - Android API compatibility
> - package lifecycle changes
> - service lifecycle
> - notification service compatibility
> - edge cases involving stale PREPARING/ACTIVE states
>
> We are proceeding **one narrow audit item at a time**.
>
> Do not redesign working architecture without concrete evidence that it is necessary.
>
> ---
>
> # 2. AFTER ALL FUNCTIONAL AUDITS ARE FINISHED
>
> Once the app is stable, tested, and hardened, we will proceed with a separate public-release/rebranding program.
>
> This future work MUST NOT be mixed into current functional bug-fix branches.
>
> Planned later stages:
>
> ## Rebranding
>
> Perform a complete rebrand of the fork, including:
>
> - application name
> - package/application identity if appropriate
> - icon
> - adaptive icon
> - splash screen
> - launcher graphics
> - colors
> - typography
> - branding strings
> - internal references that should no longer say original app name
> - about screen
> - version naming strategy
> - fork identity
> - application descriptions
> - screenshots
> - app artwork
> - possibly notification icon/branding where appropriate
>
> Do not remove required attribution to the original project.
>
> ---
>
> ## Public GitHub repository
>
> Create a polished **public GitHub repository** for the fork.
>
> The public repository should include:
>
> - professional README
> - project overview
> - screenshots
> - feature list
> - installation instructions
> - Shizuku setup instructions
> - root mode instructions if retained
> - supported Android versions
> - Samsung/Android 16 notes
> - troubleshooting
> - known limitations
> - changelog
> - release notes
> - audit history
> - testing notes
> - architecture overview where useful
> - security/reliability notes
> - contribution instructions if appropriate
> - issue-reporting guidance
> - license information
> - build instructions
> - credits
> - acknowledgments
> - original upstream attribution
> - links to upstream project
> - fork history
>
> The GitHub page should look polished and professional rather than like a raw development fork.
>
> ---
>
> ## Credits / attribution
>
> The original EnforceDoze developers and contributors must receive proper credit.
>
> Include:
>
> - upstream project name
> - upstream repository
> - original author/developer attribution
> - contributors where appropriate
> - license attribution
> - explanation that this project is a fork
> - a clear section describing changes made by this fork
> - no misleading claim that the fork is the original project
>
> Preserve all required legal/license notices.
>
> ---
>
> ## Changelog / audit logs
>
> Create clear documentation for:
>
> - important bug fixes
> - concurrency fixes
> - Android 16 compatibility work
> - Samsung-specific observations
> - Shizuku behavior
> - wake/unlock handling
> - package restoration
> - notification restoration
> - physical device-state restoration
> - durable-state recovery
> - tests performed on real hardware
> - known edge cases not directly reproduced
>
> We should be transparent about what was:
>
> - source-reviewed
> - build-tested
> - device-tested
> - naturally reproduced
> - structurally fixed but not deterministically reproduced
>
> Do not falsely claim a race was reproduced when it was only proven by code inspection.
>
> ---
>
> ## Donation page
>
> Later update donation/support links both:
>
> - inside the Android app
> - in the GitHub repository
>
> Before changing donation behavior, inspect:
>
> - current donation implementation
> - upstream licensing expectations
> - attribution requirements
> - whether original donation links should remain or be acknowledged
> - whether the fork should have a separate donation/support link
> - wording to avoid misleading users
>
> Do this carefully and ethically.
>
> ---
>
> ## UI redesign / tweaks
>
> Only after functional reliability work is finished:
>
> - inspect current screens
> - modernize UI incrementally
> - improve spacing
> - typography
> - colors
> - navigation
> - dialogs
> - settings organization
> - usability
> - visual polish
> - Android 16 compatibility
> - dark/light theme behavior
> - accessibility where practical
>
> Do not introduce UI redesign while functional audits are still active.
>
> ---
>
> # 3. MY ROLE / EXPERIENCE
>
> I am not an Android developer.
>
> I need:
>
> - exact commands
> - beginner-safe instructions
> - one stage at a time
> - explanations of what each result means
> - no assumption that I know Git/Gradle/ADB internals
> - no large command dumps unless absolutely necessary
>
> Claude CLI is used primarily for implementation/debugging.
>
> ChatGPT's role is to act as the independent reviewer:
>
> 1. inspect source and diffs independently;
> 2. challenge Claude's assumptions;
> 3. reject incomplete or over-broad fixes;
> 4. protect the known-good architecture;
> 5. approve or reject builds;
> 6. guide device testing;
> 7. verify Git status and commit contents;
> 8. approve commits only after testing;
> 9. approve pushes only after explicit confirmation.
>
> Do not accept Claude's explanation as proof if the code/diff can be inspected directly.
>
> ---
>
> # 4. OPERATING PRINCIPLES
>
> These rules are mandatory.
>
> 1. Preserve known-good architecture unless there is concrete evidence to change it.
> 2. Prefer read-only investigation before making changes.
> 3. Before destructive/configuration-changing operations:
>    - determine current state;
>    - identify dependencies;
>    - identify rollback;
>    - explain what will change.
> 4. Make one controlled change at a time.
> 5. Validate after every meaningful change.
> 6. Do not combine unrelated bugs into one branch.
> 7. Do not commit before:
>    - source review;
>    - diff review;
>    - `git diff --check`;
>    - successful build;
>    - relevant device test.
> 8. Do not push before explicit approval from me.
> 9. Never merge into `master` unless I explicitly request it.
> 10. Do not rewrite working areas just for style.
> 11. Prefer event-driven logic over timers/polling.
> 12. Avoid broad global locks unless clearly necessary.
> 13. Do not hide failed state restoration by clearing durable markers.
> 14. Durable state journals must survive backend failure/process interruption where architecture permits.
> 15. Always distinguish:
>     - logically ACTIVE
>     - physically forced Android deep idle
>     - owned Doze session
>     - maintenance transition
>     - fresh session
>     - deferred session
> 16. No secrets/passwords/API keys should ever be printed.
> 17. Do not commit temporary review `.diff` or `.patch` files.
>
> ---
>
> # 5. REPOSITORIES / PATHS
>
> Upstream:
>
> `Akylas/EnforceDoze`
>
> My fork:
>
> `rmpsdroid/EnforceDoze`
>
> Windows repo:
>
> `D:\AndroidProjects\EnforceDoze`
>
> Package:
>
> `com.akylas.enforcedoze.fork`
>
> Debug APK:
>
> `D:\AndroidProjects\EnforceDoze\app\build\outputs\apk\debug\app-debug.apk`
>
> ---
>
> # 6. MASTER / BASELINES
>
> `master` MUST remain untouched.
>
> Known-good master baseline:
>
> `a5b7c4acc7a9d8b9f48b906be66c4792dd2cd77b`
>
> This was verified after the latest push with:
>
> `git rev-parse master`
>
> and it still returned exactly:
>
> `a5b7c4acc7a9d8b9f48b906be66c4792dd2cd77b`
>
> Historical audit baseline:
>
> `audit/claude-current`
>
> Commit:
>
> `fda26b14176480927db22271644a7096bbc9c285`
>
> ---
>
> # 7. DEVICE
>
> Primary real-world validation device:
>
> **Samsung Galaxy S26 Ultra**
>
> - Android API 36
> - Samsung One UI
> - Shizuku is preferred execution backend
>
> ADB currently working endpoint:
>
> `30.30.30.234:41335`
>
> Old endpoint:
>
> `30.30.30.234:40189`
>
> The old endpoint may still appear as `offline`.
>
> Therefore while both endpoints exist, always use:
>
> `adb.exe -s 30.30.30.234:41335 ...`
>
> Do not use generic `adb.exe shell ...` until only one device exists.
>
> ---
>
> # 8. COMMAND FORMAT
>
> When I am at:
>
> `PS D:\AndroidProjects\EnforceDoze>`
>
> give me only the PowerShell command.
>
> When I am at:
>
> `C:\adb>`
>
> give me only:
>
> `adb.exe ...`
>
> Do not repeat the prompt text.
>
> Proceed one stage at a time and wait for my output.
>
> ---
>
> # 9. EXPECTED UNTRACKED REVIEW FILES
>
> These four files are intentionally untracked:
>
> - `fresh-force-late-settle-review-v1.diff`
> - `fresh-force-late-settle-review-v2.diff`
> - `fresh-force-late-settle-review-v3.diff`
> - `notification-toggle-race-v1-uncommitted.patch`
>
> They must NOT be committed unless explicitly requested.
>
> A clean project status may therefore still show these four `??` files.
>
> ---
>
> # 10. IMPORTANT PREVIOUSLY TESTED/PUSHED FIXES
>
> These are already tested/pushed and should be treated as known-good history unless new evidence contradicts them.
>
> ## Phase 1 wake restoration
>
> Commits include:
>
> - `5dd3ef...`
> - `dc0200...`
> - `a54be7...`
> - `5c55b03`
>
> ---
>
> ## Diagnostic logging
>
> `b78240449a7a993830f9f7252d3905715accf0a1`
>
> ---
>
> ## USER_PRESENT handling
>
> `e94d85036b2df9e35f43edf74b10a10bc28b6f47`
>
> ---
>
> ## Call recovery
>
> Commits:
>
> - `cb788cf`
> - `2b09fa4`
> - `113e0651923449a15137944e32a9e818e39c4b57`
>
> ---
>
> ## Lockscreen sensor behavior
>
> `fb2e9b1ff6dd3ace51ca997f067cf12b27701134`
>
> ---
>
> ## Package lifecycle
>
> - `777ae22`
> - `9e556fda9f5b8e39126f376ab862fb06eb51c917`
>
> ---
>
> ## Stats crash
>
> `65825d718f3aabfb3f421b4cedc9f274f309bee6`
>
> ---
>
> ## Lockscreen Doze resume
>
> Commits include:
>
> - `4de3ffd`
> - `a8fe1d0`
> - `76861db`
> - `2212f29`
> - `b091745b8801d873dfeb35152f2a70a4d536a5f6`
>
> ---
>
> ## Fresh force-result / PREPARING handling
>
> - `ea4c432`
> - `2855424d2912095e246b47c48875ce78bb6b3e8f`
>
> ---
>
> ## Shizuku unavailable handling
>
> `6b2a34c4686150879ce735f7078ccb53fd6009e4`
>
> ---
>
> ## Owned-session reforce
>
> `70ac1a20111cb379f64ece605ab0939f4ef3e536`
>
> This protects async owned reforce against the session ending while the command is still running.
>
> ---
>
> ## Locked-wake physical release
>
> `a9a1227c4ff2522af345340b929e0f79325944ee`
>
> Important behavior:
>
> When `waitForUnlock=true`:
>
> - screen-on while still locked physically UNFORCEs Android;
> - logical owned session remains intact;
> - session epoch/generation remains;
> - if screen turns off again before unlock, the same owned session can reforce;
> - USER_PRESENT performs final restore/unforce/EXIT.
>
> ---
>
> ## Late fresh force-idle settlement
>
> `dd3ded441cf1dfc9277e23ec165a99c8981ab780`
>
> Problem:
>
> Fresh shell force-idle could report success while `PowerManager` still reported not idle; older code could clear PREPARING before a deep-idle broadcast arrived ~47 ms later.
>
> Fix:
>
> - two-signal in-memory settlement latch
> - exact `entryAttemptToken`
> - no timer
> - no polling
>
> The stochastic `accepted_pending_confirmation` path was not naturally re-hit during tests, so do not claim deterministic device reproduction.
>
> ---
>
> ## Android 16 notification Binder fallback
>
> `a93541c14594b4fe387544aba307a22c8952fa6c`
>
> On Samsung API 36, hidden reflection field:
>
> `TRANSACTION_setNotificationsEnabledForPackage`
>
> was missing.
>
> Framework inspection/decdump showed:
>
> - transaction 17 = setter
> - transaction 19 = getter
>
> An exact API-36 Binder transaction fallback was added.
>
> Device tested by disabling/restoring Play Store notifications.
>
> ---
>
> ## Notification command serializer
>
> `0a2aefc0b8ffeb5acf357e4ca54c24e63d1fd7df`
>
> Reason:
>
> Shizuku `executeCommand` launches operations independently; notification disable/enable operations could finish out of order.
>
> Added a single-slot latest-wins serializer.
>
> Device mechanics validated.
>
> The exact opposite-target overlap was not naturally reproduced because commands usually complete in roughly 12–30 ms.
>
> ---
>
> ## Generic physical state command serializer
>
> `8555b9c9d6bd76575aeda60937e9f7d22539de47`
>
> Commit message:
>
> `fix: serialize generic device state commands`
>
> It serialized command ordering for:
>
> - mobile data
> - Wi-Fi
> - battery saver
> - airplane mode
> - Bluetooth
> - GPS
>
> Generic `StateOpSlot` latest-wins model:
>
> - per-toggle
> - one in-flight command
> - one pending command
> - newer pending target supersedes older pending target
> - superseded callback receives `-3`
> - actual in-flight callback is never fabricated
> - locks are not held while physical shell work executes
>
> Battery Saver device validation passed.
>
> Exact opposite-target overlap was not naturally reproduced.
>
> Status:
>
> **FIXED / TESTED / PUSHED**
>
> ---
>
> # 11. SENSOR SERIALIZER AUDIT
>
> This audit is complete.
>
> Relevant state:
>
> - `sensorOpLock`
> - `sensorOpInFlight`
> - `pendingSensorTarget`
> - `pendingSensorCallback`
> - `pendingSensorLabel`
> - `lockscreenSensorOverrideActive`
>
> `requestSensorState` is already latest-wins.
>
> Findings:
>
> 1. Queued reapply label can become slightly misleading diagnostically, but no physical-state or callback corruption was found.
> 2. `lockscreenSensorOverrideActive` has a minor synchronization inconsistency, but call-site inspection showed it only controls cancellation diagnostic behavior.
>
> Conclusion:
>
> **PASS / NO FIX**
>
> Do not reopen without new evidence.
>
> ---
>
> # 12. LATEST COMPLETED FIX — GENERATION-SAFE DEVICE STATE RESTORES
>
> Branch:
>
> `fix/device-state-generation-v1`
>
> Commit:
>
> `d369e4e490a0cb5cd02c3eb4d5ee2fdf594ce4f8`
>
> Commit message:
>
> `fix: make device-state restores generation-safe`
>
> Remote branch was pushed and verified.
>
> Remote SHA:
>
> `d369e4e490a0cb5cd02c3eb4d5ee2fdf594ce4f8`
>
> Master remained unchanged.
>
> Status:
>
> **FIXED / BUILT / DEVICE-TESTED / COMMITTED / PUSHED**
>
> ---
>
> # 13. GENERATION BUG THAT WAS FIXED
>
> Before this fix, physical command serializers prevented old shell commands from landing in the wrong order, but durable restore markers could still be corrupted.
>
> Old logic:
>
> `DozeStateStore.markApplied(key, previousValue)`
>
> stored:
>
> - pre-value
> - applied=true
> - applied timestamp
>
> but no generation.
>
> `clearApplied(key)`
>
> blindly removed the marker.
>
> Example race:
>
> 1. Old session restore starts for Wi-Fi.
> 2. Restore command is async.
> 3. New session begins.
> 4. New session calls `markApplied(KEY_WIFI, newPre)`.
> 5. New generation debt now exists.
> 6. Old restore callback completes successfully.
> 7. Old callback blindly clears `KEY_WIFI`.
> 8. New session's durable restore debt disappears.
>
> There was also a maintenance adoption race:
>
> - old restore pass enumerates key
> - maintenance reapply creates newer generation
> - old pass could snapshot newer generation unless selection/snapshot/dispatch are serialized
>
> There was also a fresh-session correctness problem:
>
> A truly new session could begin while old restore was still physically completing and read the still-restricted state as the new “pre-Doze” state.
>
> Generation alone was therefore insufficient.
>
> ---
>
> # 14. GENERATION FIX IMPLEMENTATION
>
> ## DozeStateStore
>
> Added generation storage:
>
> `PREFIX_GENERATION = "gen."`
>
> `markApplied` is synchronized.
>
> Each call increments the existing generation and atomically stores:
>
> - previous value
> - applied marker
> - generation
> - applied timestamp
>
> Added:
>
> `AppliedKeySnapshot`
>
> containing:
>
> - `key`
> - `previousValue`
> - `generation`
>
> Added synchronized:
>
> `getAppliedKeySnapshot(key, defaultPreviousValue)`
>
> Returns null if key is not currently applied.
>
> Added synchronized:
>
> `clearAppliedIfGeneration(key, expectedGeneration)`
>
> Only clears the applied marker if the generation still matches.
>
> Generation itself is retained rather than reset.
>
> Missing generation defaults to `0` for compatibility with older stored markers.
>
> ---
>
> # 15. RESTORE INTEGRATION
>
> `performRestore` now receives the captured previous value.
>
> Generic six physical state keys plus sensors/biometrics restore from the captured snapshot.
>
> `onRestoreFinished(key, generation, exitCode)`:
>
> - preserves existing special Doze guards for:
>   - all sensors
>   - biometrics
>   - motion
> - on `exitCode == 0`, calls:
>   `clearAppliedIfGeneration`
> - if generation changed:
>   - logs `RESTORE_SUPERSEDED`
>   - preserves the newer durable marker
>
> ---
>
> # 16. DEFAULT PRE-DOZE VALUES
>
> Compatibility defaults match old behavior:
>
> - AIRPLANE → false
> - BATTERY_SAVER → false
> - other generic physical states → true
>
> ---
>
> # 17. RESTORE SELECTION / MAINTENANCE RACE FIX
>
> A first version was rejected because:
>
> `getAppliedKeys()`
>
> was outside `physicalEntryLock`.
>
> That left the maintenance adoption race open.
>
> Final approved implementation performs all of these in one uninterrupted `physicalEntryLock` section:
>
> 1. `getAppliedKeys`
> 2. optional key filtering
> 3. `stateRestoreInFlight.add`
> 4. generation snapshot
> 5. restore dispatch
>
> The lock is NOT held while waiting for shell completion.
>
> This was independently inspected and approved.
>
> ---
>
> # 18. FRESH-ENTRY DEBT BARRIER
>
> Existing:
>
> `ownedReforceFreshEntryDeferred`
>
> was generalized to:
>
> `debtFreshEntryDeferred`
>
> Added:
>
> `armDebtFreshEntryIntent(reason)`
>
> and:
>
> `maybeConsumeDebtFreshEntryIntent(reason)`
>
> Fresh entry is deferred if either:
>
> - owned reforce is unresolved, or
> - fresh entry is otherwise valid but package/state restore debt remains
>
> Debt gate conditions:
>
> `hasAppliedSuspendedPackages() || hasPendingRestore()`
>
> These gates exist in BOTH actual fresh-session claim paths:
>
> 1. tunable fallback before `setInDoze(true)` and fresh journal claim
> 2. `beginPrivilegedFreshEntry()` before `beginForceIdleAttempt`
>
> This is important because it prevents a genuinely new session from capturing still-restricted physical state as its new baseline.
>
> ---
>
> # 19. MAINTENANCE BEHAVIOR
>
> Maintenance transitions are NOT treated as fresh sessions.
>
> `MAINTENANCE_RESTORE_KEYS` includes:
>
> - AIRPLANE
> - BLUETOOTH
> - GPS
> - WIFI
> - MOBILE_DATA
> - BATTERY_SAVER
> - ALL_SENSORS
>
> On deep-idle exit while owned session and not already maintenance:
>
> - restore maintenance keys
> - set maintenance=true
>
> On deep-idle re-entry during maintenance:
>
> `enterDozeHandleNetwork(context)`
>
> is used.
>
> The fresh debt gate was intentionally NOT inserted into `actualEnterDozeHandleNetwork`, preserving maintenance semantics.
>
> ---
>
> # 20. DEBT INTENT CANCELLATION
>
> `invalidateDesiredEntry()` clears:
>
> - `shizukuFreshEntryDeferred`
> - `debtFreshEntryDeferred`
>
> Therefore deferred fresh entry is cancelled by events such as:
>
> - screen on
> - call
> - charging policy
> - custom period changes
> - session teardown
>
> ---
>
> # 21. DEBT COMPLETION RETRY
>
> Package restoration completion calls:
>
> - `maybeRetryDeferredShizukuEntry("package_debt_cleared")`
> - `maybeConsumeDebtFreshEntryIntent("package_debt_cleared")`
>
> State restoration completion calls:
>
> - `maybeRetryDeferredShizukuEntry("restore_debt_cleared")`
> - `maybeConsumeDebtFreshEntryIntent("restore_debt_cleared")`
>
> `maybeConsumeDebtFreshEntryIntent`:
>
> 1. no-op if flag false
> 2. clears if service stopping
> 3. checks owned-reforce unresolved state
> 4. returns if unresolved
> 5. returns if package debt remains
> 6. returns if state debt remains
> 7. CAS true→false
> 8. one winner calls:
>    `reevaluateEntryAfterCleanup()`
>
> Even though the method releases `physicalEntryLock` before final debt/CAS checks, source analysis concluded this is safe because actual fresh claim paths recheck critical gates under `physicalEntryLock`.
>
> ---
>
> # 22. GENERATION FIX BUILD / DEVICE TESTS
>
> `git diff --check`:
>
> PASS
>
> `.\gradlew assembleDebug`:
>
> PASS
>
> Warnings were unrelated.
>
> APK installed successfully.
>
> ---
>
> # 23. NORMAL BATTERY-SAVER DEVICE TEST
>
> Temporary setting:
>
> `Turn on Battery Saver in Doze = ON`
>
> Entry:
>
> - `mState=IDLE`
> - `mLightState=OVERRIDE`
> - `low_power=1`
>
> Wake/unlock:
>
> - `mState=ACTIVE`
> - `mLightState=ACTIVE`
> - `low_power=0`
>
> Generation-aware logs included:
>
> `RESTORE_SUCCESS allSensors exit=0 gen=1`
>
> `RESTORE_SUCCESS batterySaver exit=0 gen=1`
>
> This confirmed normal generation-aware restore.
>
> ---
>
> # 24. SHIZUKU FAILURE / DURABLE DEBT TEST
>
> While device was in Doze with battery saver enabled:
>
> Shizuku server was deliberately killed.
>
> When phone woke while Shizuku was unavailable:
>
> - package restore failed
> - state restore failed
> - markers remained
>
> Logs included:
>
> `RESTORE_FAILED batterySaver exit=-1, marker kept for retry`
>
> `RESTORE_FAILED allSensors exit=-1, marker kept for retry`
>
> Package unsuspend also failed.
>
> Apps appeared greyed out because the package debt was intentionally unresolved.
>
> After Shizuku restarted:
>
> - packages unsuspended
> - apps became enabled again
> - state debt retried
> - battery saver returned to 0
> - current generation succeeded
>
> Logs:
>
> `RESTORE_SUCCESS allSensors exit=0 gen=3`
>
> `RESTORE_SUCCESS batterySaver exit=0 gen=3`
>
> This strongly validated:
>
> - failed callbacks do not erase debt
> - journal survives backend failure
> - retry works
> - newer generation succeeds
>
> The exact old-generation callback/new-generation callback overlap was NOT naturally reproduced.
>
> Do not claim it was.
>
> ---
>
> # 25. FINAL CLEAN REGRESSION
>
> Temporary Battery Saver preference was restored to:
>
> `turnOnBatterySaverInDoze=false`
>
> Normal preference:
>
> `waitForUnlock=true`
>
> Final clean screen-off cycle:
>
> Display confirmed:
>
> - `Display State=OFF`
> - `mScreenState=OFF`
> - `mActualState=OFF`
>
> Device idle:
>
> - `mForceIdle=true`
> - `mState=IDLE`
> - `mLightState=OVERRIDE`
>
> Battery Saver:
>
> `0`
>
> This proved fresh entry works while battery-saver preference remains off.
>
> ---
>
> # 26. FINAL CLEAN UNLOCK REGRESSION
>
> At first the phone was thought to be unlocked, but Android keyguard still reported:
>
> - `showing=true`
> - `mIsShowing=true`
>
> Therefore the apparent failure to leave Doze at that moment was NOT valid evidence of a bug.
>
> After the phone was genuinely unlocked, keyguard reported:
>
> - `showing=false`
> - `mIsShowing=false`
>
> Final device state became:
>
> - `mForceIdle=false`
> - `mState=ACTIVE`
> - `mLightState=ACTIVE`
>
> Logs showed:
>
> `Screen ON received`
>
> `HARD_BLOCK_RESTORE_START reason=screen on count=232`
>
> `HARD_BLOCK_BATCH unsuspend count=232 exit=0`
>
> `Temporarily leaving forced deep idle for the lock screen`
>
> Then:
>
> `UNLOCK received true`
>
> `handleScreenOn`
>
> `Last known Doze state: IDLE`
>
> `Exiting Doze (owned session), physical state: ACTIVE`
>
> and finally:
>
> `DOZE_UNFORCE_FINISHED exit=0`
>
> Package restore also completed successfully.
>
> Therefore final normal regression:
>
> **PASS**
>
> ---
>
> # 27. IMPORTANT CORRECTION ABOUT SHIZUKU RECOVERY BUG
>
> During the earlier Shizuku-failure test, a possible bug was observed where:
>
> - service logical state appeared ACTIVE
> - `mForceIdle=true`
> - Android remained IDLE after Shizuku returned
>
> This was initially considered a confirmed:
>
> “Shizuku recovered ACTIVE physical leaveDoze durability” bug.
>
> However, the later clean regression showed that keyguard state had not been verified during the earlier test.
>
> Because `waitForUnlock=true`, being physically awake is not equivalent to being logically unlocked.
>
> Therefore:
>
> ## Do NOT currently treat this bug as conclusively proven.
>
> Instead, the next audit should deliberately and deterministically test:
>
> **Shizuku backend failure → screen on → real keyguard unlock → Shizuku recovery → physical leaveDoze behavior**
>
> with keyguard state verified at every stage.
>
> This remains a deferred/high-priority audit item, but its status should now be:
>
> **SUSPECTED / NEEDS DEDICATED REPRODUCTION**
>
> not “confirmed”.
>
> ---
>
> # 28. CURRENT COMPLETED BRANCH
>
> Branch:
>
> `fix/device-state-generation-v1`
>
> Commit:
>
> `d369e4e490a0cb5cd02c3eb4d5ee2fdf594ce4f8`
>
> Remote verified with:
>
> `git ls-remote origin refs/heads/fix/device-state-generation-v1`
>
> which returned the same SHA.
>
> Working tree afterward contained only the four expected untracked review files.
>
> Master remained:
>
> `a5b7c4acc7a9d8b9f48b906be66c4792dd2cd77b`
>
> No merge into master.
>
> ---
>
> # 29. NEXT AUDIT ITEM
>
> Proceed next with:
>
> ## Shizuku recovery while ACTIVE / physical leaveDoze durability
>
> But start **read-only**.
>
> Do not create a fix branch immediately unless source investigation confirms a defect.
>
> The first goal is to inspect:
>
> - Shizuku availability callback
> - what happens on availability transition `false → true`
> - whether package debt is retried
> - whether state debt is retried
> - whether physical Doze exit debt exists
> - whether logical `isInDoze()` can become false before physical unforce succeeds
> - whether a failed locked-wake unforce is durably remembered
> - whether USER_PRESENT later retries physical unforce
> - whether backend recovery while the screen is already unlocked retries leaveDoze
> - whether the existing deferred Shizuku mechanism covers entry only, exit only, or both
> - whether session ownership is already cleared before physical exit succeeds
> - whether a failed Shizuku exit can become orphaned
>
> Do NOT assume the bug exists.
>
> First reconstruct the state machine from source.
>
> ---
>
> # 30. DEDICATED REPRO TEST DESIGN FOR NEXT ITEM
>
> If source inspection suggests the bug is possible, test deterministically.
>
> Possible controlled sequence:
>
> 1. Confirm Shizuku available.
> 2. Confirm phone ACTIVE and unlocked.
> 3. Clear logcat.
> 4. Turn screen off.
> 5. Confirm display truly OFF.
> 6. Confirm:
>    - `mForceIdle=true`
>    - `mState=IDLE`
> 7. While still in Doze, kill Shizuku server.
> 8. Confirm Shizuku unavailable.
> 9. Wake phone.
> 10. Check keyguard:
>     - expect showing=true while locked
> 11. Observe what EnforceDoze tries to do.
> 12. Fully unlock phone.
> 13. Confirm keyguard:
>     - `showing=false`
>     - `mIsShowing=false`
> 14. While Shizuku is still unavailable, observe:
>     - logical service state
>     - `mForceIdle`
>     - deviceidle state
>     - restore debt
> 15. Restart Shizuku.
> 16. Confirm availability true.
> 17. Observe whether:
>     - package restore retries
>     - state restore retries
>     - physical unforce retries
> 18. Verify:
>     - `mForceIdle=false`
>     - `mState=ACTIVE`
> 19. If it remains forced:
>     - capture source logs before manually running `dumpsys deviceidle unforce`
>
> Do not infer unlock based on visual appearance.
>
> Always verify keyguard state with `dumpsys window policy`.
>
> ---
>
> # 31. CURRENT DEFERRED AUDIT LIST
>
> Continue roughly in this order, but adjust when evidence requires.
>
> Already done:
>
> - handleScreenOn/session-exit barrier — PASS / NO FIX
> - notification shell disable/enable race — FIXED / TESTED
> - generic device-state command reorder — FIXED / TESTED / PUSHED
> - sensor serializer narrow concurrency — PASS / NO FIX
> - generic generation races — FIXED / TESTED / PUSHED
>
> Next / deferred:
>
> 1. Shizuku recovered ACTIVE physical leaveDoze durability — dedicated reproduction needed
> 2. generic SharedPreferences commit-result handling
> 3. maintenance async restore/reapply behavior
> 4. maintenance process-death recovery
> 5. Shizuku `newProcess` deprecation / Android API behavior
> 6. stdout/stderr pipe deadlock risk
> 7. onDestroy async restore / boot timing
> 8. notification blocklist exact-set correctness
> 9. biometric pre-state assumptions
> 10. root orphan processes
> 11. `getPlayingPackageName` missing callback
> 12. root child-process survival
> 13. pre-N tracked release protocol
> 14. tunable callback absence
> 15. marker-stuck edge cases
> 16. PREPARING phantom-boot risk
>
> Do not treat this list as immutable. New evidence may insert a higher-priority item.
>
> ---
>
> # 32. GENERAL COMMAND CONCURRENCY MODEL
>
> Shizuku commands can execute in separate threads/processes.
>
> Never assume command dispatch order equals completion order.
>
> Known serializers now exist for:
>
> - notification state
> - generic physical device-state toggles
> - sensors
>
> When auditing a new async path, always ask:
>
> - Can operation A and B overlap?
> - Can stale A complete after newer B?
> - Does callback A mutate durable state belonging to B?
> - Is there a generation/token/session check?
> - Is there durable debt if backend execution fails?
> - Can the service claim logical success before physical success?
> - Can process death lose the unresolved physical action?
>
> ---
>
> # 33. DEVICE-STATE KEYS
>
> Generic physical state commands include:
>
> ## Mobile data
>
> `svc data`
>
> ## Wi-Fi
>
> `svc wifi`
>
> with pre-Q WifiManager fallback
>
> ## Battery saver
>
> `settings put global low_power`
>
> ## Airplane mode
>
> `settings put global airplane_mode_on ...`
>
> plus:
>
> `am broadcast -a android.intent.action.AIRPLANE_MODE --ez state ...`
>
> ## Bluetooth
>
> `svc bluetooth`
>
> ## GPS/location
>
> `settings put secure location_mode`
>
> These now use generic state serialization and generation-safe restore markers.
>
> ---
>
> # 34. NOTIFICATION API-36 SPECIAL CASE
>
> On Samsung Android 16/API 36:
>
> reflection lookup of hidden transaction constants fails.
>
> Fallback Binder transaction is intentionally restricted to exact Android 16/API 36 behavior.
>
> Do not generalize that transaction number to arbitrary Android versions without inspecting framework behavior first.
>
> ---
>
> # 35. BUILD WARNINGS
>
> Existing warnings include:
>
> - Gradle restricted native `System::load`
> - deprecated Media APIs in NotificationService
> - unsafe operations in DozeTunableHandler
> - deprecated Gradle features
>
> These did not block build.
>
> Do not mix warning cleanup into functional race-fix branches unless warning is directly relevant to the current audit item.
>
> ---
>
> # 36. GIT WORKFLOW
>
> For every new fix:
>
> 1. verify current branch/head
> 2. create a narrow feature branch
> 3. inspect source
> 4. let Claude implement only after architecture is understood
> 5. inspect diff independently
> 6. run:
>    `git diff --check`
> 7. build:
>    `.\gradlew assembleDebug`
> 8. install APK
> 9. device-test relevant behavior
> 10. restore temporary preferences
> 11. verify regression
> 12. inspect:
>    `git status --short`
> 13. stage only intended files
> 14. run:
>    `git diff --cached --check`
> 15. run:
>    `git diff --cached --stat`
> 16. wait for my explicit:
>    `approve commit`
> 17. commit
> 18. verify status/head
> 19. wait for explicit:
>    `approve push`
> 20. push branch
> 21. verify remote SHA
> 22. verify master unchanged
>
> Never commit review patch files.
>
> Never merge feature branch into master unless I explicitly request it.
>
> ---
>
> # 37. TEST RESULT TERMINOLOGY
>
> Use precise labels.
>
> Examples:
>
> ### Fully reproduced
>
> `REPRODUCED / FIXED / DEVICE-TESTED`
>
> Only use when actual failure timing was observed.
>
> ### Structurally proven, timing not naturally reproduced
>
> Use wording such as:
>
> `PASS — implementation and device mechanics validated. Exact opposite-target in-flight overlap not naturally reproduced.`
>
> ### Source-only
>
> `SOURCE REVIEW PASS`
>
> ### Built
>
> `BUILD PASS`
>
> ### Device tested
>
> `DEVICE TEST PASS`
>
> Do not exaggerate evidence.
>
> ---
>
> # 38. NORMAL USER SETTINGS TO PRESERVE
>
> Important known normal setting:
>
> `waitForUnlock=true`
>
> Temporary testing setting:
>
> `turnOnBatterySaverInDoze`
>
> was restored to:
>
> `false`
>
> Notification blocklist currently includes Play Store as part of prior notification testing.
>
> Do not casually change/remove existing blocklist data.
>
> ---
>
> # 39. IMPORTANT ANDROID TESTING RULE
>
> Samsung lockscreen/AOD behavior can be misleading.
>
> Never assume:
>
> - screen is off because it looks black;
> - screen is unlocked because UI appears;
> - Android is ACTIVE because display is awake.
>
> Use actual system state.
>
> Useful checks:
>
> Display:
>
> `adb.exe -s 30.30.30.234:41335 shell dumpsys display | findstr /I /C:"Display State=" /C:"mScreenState=" /C:"mActualState="`
>
> Keyguard:
>
> `adb.exe -s 30.30.30.234:41335 shell dumpsys window policy | findstr /I "keyguard showing occluded"`
>
> Doze:
>
> `adb.exe -s 30.30.30.234:41335 shell dumpsys deviceidle | findstr /I "mForceIdle mState="`
>
> Battery saver:
>
> `adb.exe -s 30.30.30.234:41335 shell settings get global low_power`
>
> Power/wake state may also be inspected with `dumpsys power`, but Samsung/AOD interpretation requires care.
>
> ---
>
> # 40. LOGGING
>
> For focused app logs:
>
> `adb.exe -s 30.30.30.234:41335 logcat -d -s ForceDozeService:I ShizukuHandler:I *:S`
>
> Useful existing diagnostic strings include:
>
> - `Screen OFF received`
> - `Screen ON received`
> - `UNLOCK received`
> - `Entering Doze`
> - `Now forced in to deep idle mode`
> - `ACTION_DEVICE_IDLE_MODE_CHANGED`
> - `Current (Deep) state`
> - `RESTORE_PENDING`
> - `RESTORE_SUCCESS`
> - `RESTORE_FAILED`
> - `RESTORE_SUPERSEDED`
> - `HARD_BLOCK_RESTORE_START`
> - `HARD_BLOCK_BATCH`
> - `DOZE_UNFORCE_FINISHED`
> - Shizuku availability changes
>
> ---
>
> # 41. HOW TO RESPOND TO ME
>
> Be direct and structured.
>
> I am not a developer.
>
> Give:
>
> - one stage at a time
> - exact command
> - what we expect
> - brief explanation of significance
>
> Wait for my pasted output before proceeding.
>
> Do not dump ten future commands at once during device testing.
>
> When source investigation is needed, tell me what to ask Claude or what exact command to run.
>
> Independently inspect Claude's changes before approving them.
>
> Do not assume implementation correctness because it builds.
>
> Do not commit/push without explicit approval.
>
> ---
>
> # 42. WHERE TO START IN THIS NEW CHAT
>
> Start with the next audit item:
>
> ## Shizuku recovery while ACTIVE / physical leaveDoze durability
>
> But **do not modify code yet**.
>
> Begin with a read-only source audit.
>
> First verify current Git branch and working-tree status.
>
> Then inspect all code involved in:
>
> - Shizuku availability callback
> - Shizuku binder death
> - availability recovery
> - screen-on path
> - USER_PRESENT/unlock path
> - `leaveDoze`
> - `exitDoze`
> - physical unforce callback
> - owned-session teardown
> - Shizuku deferred intents
> - restoration debt retry
>
> The goal is to answer:
>
> > Can the app become logically ACTIVE while Android remains physically `mForceIdle=true` after Shizuku was unavailable during exit, and if so, what exact state transition loses the retry obligation?
>
> Do not assume the answer is yes.
>
> Prove or disprove it from source first.
>
> Only after source analysis should we design a dedicated deterministic device test.
>
> If a bug is proven, create a new narrow branch, likely something like:
>
> `fix/shizuku-recovery-leave-doze-v1`
>
> but do not create it until the defect is confirmed.
>
> ---
>
> # 43. LONG-TERM RELEASE PLAN — DO NOT FORGET
>
> After all audits/fixes are complete:
>
> 1. freeze functional behavior
> 2. run full regression suite
> 3. review all branches/fixes
> 4. decide final integration strategy
> 5. complete full application rebranding
> 6. review package/application identity
> 7. redesign icon and visual assets
> 8. improve UI/settings organization
> 9. update donation/support page
> 10. establish ethical upstream attribution
> 11. preserve license/legal notices
> 12. prepare public GitHub repository
> 13. build polished README
> 14. add screenshots
> 15. add architecture notes
> 16. add installation guides
> 17. document Shizuku setup
> 18. document Android 16/Samsung specifics
> 19. publish changelog
> 20. publish audit/fix history
> 21. document known limitations
> 22. credit original developers/contributors
> 23. prepare release notes
> 24. create GitHub Releases
> 25. publish APK/release artifacts only after final signing/release process is agreed
> 26. continue UI polishing only after reliability baseline is frozen
>
> The public release should look like a maintained, professional fork—not just an accumulation of patches.
>
> ---
>
> Treat this entire prompt as the project handover state.
>
> Start with **read-only Git/source verification for the Shizuku recovery / physical leaveDoze audit**, one step at a time.

---

# PART III — HISTORICAL SOURCE SNAPSHOT B

The following is the later/alternate continuation snapshot retained for lossless historical reference.

It contains additional detailed context around the late fresh force-idle settlement work, locked-wake release test design, old build environment issue, and then-current device-test sequence.

**It is historical. PART I supersedes stale “current state,” “no push,” “master unchanged,” ADB endpoint, and next-step statements inside this quoted snapshot.**

> I am continuing a long Android EnforceDoze fork audit/hardening project from a previous ChatGPT conversation.
>
> IMPORTANT: Treat everything below as authoritative continuity context. Do not make me restart the investigation or repeat completed work.
>
> I am NOT a developer. Give me exact, beginner-safe commands, preferably one controlled stage at a time.
>
> Claude CLI is doing implementation/debugging work.
> ChatGPT's role is to independently inspect actual source/diffs, challenge Claude's reasoning, approve or reject changes, guide commits/builds/device tests, and preserve the known-good architecture.
>
> Do not accept Claude reports as proof when an actual diff/source file can be inspected.
>
> ============================================================
> PROJECT / REPOSITORY
> ============================================================
>
> Upstream:
>
>     Akylas/EnforceDoze
>
> User fork:
>
>     rmpsdroid/EnforceDoze
>
> Repository on Windows:
>
>     D:\AndroidProjects\EnforceDoze
>
> Upstream/default branch:
>
>     master
>
> CRITICAL RULE:
>
>     master must remain untouched.
>
> Upstream master known baseline SHA:
>
>     a5b7c4acc7a9d8b9f48b906be66c4792dd2cd77b
>
> Custom audit baseline:
>
>     audit/claude-current
>     fda26b14176480927db22271644a7096bbc9c285
>
> ============================================================
> WORKFLOW RULES
> ============================================================
>
> 1. Preserve upstream architecture/functionality unless there is a concrete bug.
> 2. Prefer narrow fixes, not redesigns.
> 3. Read-only investigation before changes.
> 4. One controlled change at a time.
> 5. Validate after every meaningful change.
> 6. Do not push until explicitly approved.
> 7. Do not merge into master.
> 8. Do not casually rewrite working code.
> 9. No broad privacy/security claims without audit.
> 10. Claude implementation reports are not proof. Inspect actual diff/source where possible.
>
> For Windows PowerShell commands, when I am already at:
>
>     PS D:\AndroidProjects\EnforceDoze>
>
> give only the command itself.
>
> For ADB commands, when I am already at:
>
>     C:\adb>
>
> give only:
>
>     adb.exe ...
>
> Do not duplicate the prompt text.
>
> ============================================================
> DEVICE / TEST ENVIRONMENT
> ============================================================
>
> Real test device:
>
>     Samsung Galaxy S26 Ultra
>     Android API 36
>     One UI
>     Shizuku preferred
>
> Wireless ADB endpoint:
>
>     30.30.30.234:37159
>
> Package:
>
>     com.akylas.enforcedoze.fork
>
> Debug APK:
>
>     D:\AndroidProjects\EnforceDoze\app\build\outputs\apk\debug\app-debug.apk
>
> Installed app version before latest work:
>
>     versionCode=86
>     versionName=1.10.2
>     minSdk=23
>     targetSdk=36
>
> Current phone instruction until build verification is complete:
>
>     screen ON
>     unlocked
>     home screen visible
>
> Do not start another screen-off/deviceidle test until the committed-tree
> build passes and we explicitly begin the controlled device test.
>
> ============================================================
> IMPORTANT EXISTING COMMIT HISTORY
> ============================================================
>
> Known progression:
>
> Phase1 wake restoration:
>     5dd3ef...
>     dc0200...
>     a54be7...
>     5c55b0...
>
> Diagnostic logging:
>     b78240449a7a993830f9f7252d3905715accf0a1
>
> USER_PRESENT:
>     e94d85036b2df9e35f43edf74b10a10bc28b6f47
>
> Call recovery:
>     cb788cf...
>     2b09fa...
>     113e0651923449a15137944e32a9e818e39c4b57
>
> Lockscreen sensors:
>     fb2e9b1ff6dd3ace51ca997f067cf12b27701134
>
> Package lifecycle:
>     777ae225...
>     9e556fda9f5b8e39126f376ab862fb06eb51c917
>
> Stats crash:
>     65825d718f3aabfb3f421b4cedc9f274f309bee6
>
> Lockscreen Doze resume series:
>     4de3ffd9...
>     a8fe1d01...
>     76861db4...
>     2212f292...
>     b091745b8801d873dfeb35152f2a70a4d536a5f6
>
> Fresh force-result / PREPARING work:
>     ea4c4327f05179fcc90ffb15d2e102629b099271
>     2855424d2912095e246b47c48875ce78bb6b3e8f
>
> Shizuku-unavailable fix:
>     6b2a34c4686150879ce735f7078ccb53fd6009e4
>
> Owned-session reforce:
>     70ac1a20111cb379f64ece605ab0939f4ef3e536
>
> Locked-wake physical release:
>     a9a1227c4ff2522af345340b929e0f79325944ee
>
> CURRENT LATEST COMMIT:
>
>     dd3ded441cf1dfc9277e23ec165a99c8981ab780
>
> Branch:
>
>     fix/fresh-force-late-settle-v1
>
> Parent:
>
>     a9a1227c4ff2522af345340b929e0f79325944ee
>
> Commit subject:
>
>     fix: handle late fresh force-idle settlement
>
> Diffstat:
>
>     ForceDozeService.java only
>     288 insertions(+)
>     29 deletions(-)
>
> ============================================================
> CURRENT BRANCH STATUS
> ============================================================
>
> The latest commit was made successfully:
>
>     dd3ded441cf1dfc9277e23ec165a99c8981ab780
>
> Parent verified exactly:
>
>     a9a1227c4ff2522af345340b929e0f79325944ee
>
> Current branch:
>
>     fix/fresh-force-late-settle-v1
>
> Only these files remain untracked:
>
>     fresh-force-late-settle-review-v1.diff
>     fresh-force-late-settle-review-v2.diff
>     fresh-force-late-settle-review-v3.diff
>
> They are review artifacts only and MUST NOT be committed.
>
> No push has occurred.
>
> ============================================================
> WHY THIS LATEST FIX EXISTS
> ============================================================
>
> A real-device fresh-entry race was demonstrated.
>
> At approximately:
>
>     10:06:20
>
> diagnostics showed:
>
>     force_idle_attempt_start mode=fresh token=10
>     force_idle_deep exit=0
>
> Then immediate verification said:
>
>     idleMode=false
>
> and old code classified:
>
>     semantic_rejection
>
> and cleared PREPARING.
>
> But logcat proved DeviceIdleController had actually said:
>
>     Now forced in to deep idle mode
>
> Exact real device evidence:
>
>     08-28 10:06:20.244 ShizukuHandler:
>     Now forced in to deep idle mode
>
>     08-28 10:06:20.259 ForceDozeService:
>     Now forced in to deep idle mode
>
> Then only about 47 ms after the incorrect abort:
>
>     device_idle_mode_changed_ignored
>         reason=no_owned_session
>         deepIdle=true
>
> Result while phone later awake/unlocked:
>
>     inDoze=false
>     entryPending=false
>     ownedReforcePending=false
>
> but:
>
>     mForceIdle=true
>     mState=IDLE
>     mLightState=OVERRIDE
>
> That was an orphan physical force.
>
> We manually cleaned it with:
>
>     adb shell dumpsys deviceidle unforce
>
> and confirmed:
>
>     mForceIdle=false
>     mState=ACTIVE
>     mLightState=ACTIVE
>
> with durable flags still false.
>
> ============================================================
> ROOT CAUSE
> ============================================================
>
> The force-idle shell command can finish successfully and print:
>
>     Now forced in to deep idle mode
>
> before:
>
>     PowerManager.isDeviceIdleMode()
>
> has become true.
>
> Therefore:
>
>     exit=0 + immediate idle=false
>
> is NOT sufficient evidence of semantic refusal.
>
> Additionally, Shizuku can report a nonzero/-1 outcome after a command
> may already have partially executed, so:
>
>     exit!=0 + immediate idle=false
>
> also cannot safely clear PREPARING without conservative cleanup.
>
> ============================================================
> LATEST FIX DESIGN — dd3ded4
> ============================================================
>
> Source review of the final v3 diff PASSED before commit.
>
> Only:
>
>     ForceDozeService.java
>
> was changed.
>
> No new durable key.
>
> A two-signal in-memory latch was added, guarded by:
>
>     physicalEntryLock
>
> Fields conceptually:
>
>     pendingEntryConfirmToken
>     pendingEntryCommandAccepted
>     pendingEntryIdleObserved
>
> A fresh privileged session commits only when BOTH are established for
> the same exact current entryAttemptToken:
>
>     command accepted
>     physical deep idle observed
>
> The durable:
>
>     KEY_ENTRY_PENDING
>
> remains the process-death record.
>
> ============================================================
> COMMAND RESULT CLASSIFICATION
> ============================================================
>
> Final intended verdict order:
>
> 1.
>
>     command completed
>     AND explicit controller REFUSED
>
>     -> semantic_rejection
>
> Explicit refusal takes precedence over an idle sample so we never claim
> an unrelated/natural idle state.
>
> 2.
>
>     command completed
>     AND physically idle
>
>     -> verified_success
>
> 3.
>
>     command completed
>     AND explicit controller SUCCESS
>     AND immediate idle=false
>
>     -> accepted_pending_confirmation
>
> Keep PREPARING.
> Wait for framework deep-idle observation.
>
> 4.
>
>     command completed
>     but output unknown
>     and idle=false
>
>     -> conservative physical UNFORCE cleanup
>
> 5.
>
>     command did not complete / exit!=0
>
>     -> transport_outcome_uncertain
>     -> conservative physical UNFORCE cleanup
>     -> KEY_ENTRY_PENDING must remain until unforce succeeds
>
> Explicit controller success recognised:
>
>     Now forced in to deep idle mode
>
> also tolerant of:
>
>     Now forced into deep idle mode
>
> Explicit refusal includes:
>
>     Unable to go deep idle
>
> and existing:
>
>     stopped at ...
>
> Unknown OEM wording is NOT treated as success.
>
> ============================================================
> TWO-SIGNAL ORDERING
> ============================================================
>
> Callback first:
>
>     shell callback:
>         accepted
>         idle=false
>
>     -> commandAccepted=true
>     -> keep PREPARING
>     -> no inDoze
>     -> no epoch
>     -> no generation
>     -> no ENTER
>
> later:
>
>     ACTION_DEVICE_IDLE_MODE_CHANGED deepIdle=true
>
>     -> idleObserved=true
>     -> both signals true
>     -> commit once
>
> Broadcast first:
>
>     deepIdle=true arrives first
>
>     -> idleObserved=true only
>     -> DO NOT commit yet
>
> later shell callback:
>
>     if command accepted
>         -> both signals true -> commit once
>
>     if refusal/transport uncertainty
>         -> never claim the earlier broadcast
>         -> abort or conservative cleanup
>
> ============================================================
> IMPORTANT BARRIER INVARIANT
> ============================================================
>
> During final source review we found and fixed another race.
>
> ALL three fresh successful commit routes now perform:
>
>     commitDozeSession()
>     phase/latch transition
>     commitFreshDozeSession(...)
>
> while still holding:
>
>     physicalEntryLock
>
> Routes:
>
> 1. immediate verified success
> 2. broadcast-first then callback completes pair
> 3. callback-first then idle broadcast completes pair
>
> This prevents SCREEN_ON/call/etc. from seeing:
>
>     inDoze=true
>
> before the fresh session has actually been fully established.
>
> This was independently inspected in:
>
>     fresh-force-late-settle-review-v3.diff
>
> and source review was PASS.
>
> ============================================================
> CANCELLATION / RECOVERY
> ============================================================
>
> If a command has already reported acceptance but physical confirmation
> is still pending and SCREEN_ON/call/charging/custom-period/etc.
> cancels the entry:
>
>     move to PHASE_CLEANING_UP
>     real corrective UNFORCE
>     keep KEY_ENTRY_PENDING until cleanup succeeds
>
> Process death does NOT reconstruct the in-memory latch.
>
> Existing durable recovery remains:
>
>     entryPending=true
>         -> conservative UNFORCE
>         -> checked clear
>         -> ordinary policy
>
> No timers.
> No Thread.sleep.
> No polling.
> No arbitrary 100/500/1000 ms delay.
> No Samsung-specific workaround.
> No min_time_to_alarm changes.
> No alarm cancellation.
> No fake ENTER/EXIT.
>
> ============================================================
> LOCKED-WAKE RELEASE WORK — a9a1227
> ============================================================
>
> Prior commit:
>
>     a9a1227c4ff2522af345340b929e0f79325944ee
>
> subject:
>
>     fix: release forced idle during owned lockscreen wakes
>
> Its purpose:
>
> When waitForUnlock=true and a committed owned Doze session wakes only
> to the lock screen:
>
>     preserve same logical session
>     preserve same epoch
>     preserve same package generation
>     temporarily restore lockscreen-sensitive restrictions
>     physically UNFORCE deep idle
>     no EXIT
>     no new ENTER
>     no new generation
>
> If the screen goes OFF again without USER_PRESENT:
>
>     reapply temporary restrictions
>     keep same epoch/generation
>     perform genuine owned-session physical reforce
>
> USER_PRESENT finally:
>
>     restores
>     unforces
>     one EXIT
>     ends epoch once
>
> ============================================================
> REAL-DEVICE PASS ALREADY OBTAINED FOR a9a1227
> ============================================================
>
> Earlier real-device test proved:
>
> Fresh entry:
>
>     force_idle_deep exit=0
>     verified success
>     session_epoch_started epoch=2
>     package generation gen=153
>
> Locked SCREEN_ON:
>
>     screen_on waitForUnlock=true locked=true
>     temporary package unsuspend same gen=153
>     sensor restore
>
> Physical-release path:
>
>     lockscreen_release_start epoch=2 plan=shizuku
>     lockscreen_unforce exit=0
>     lockscreen_release_result
>         idleMode=false
>         lifecycle=locked_wake
>
> Then USER_PRESENT happened before SCREEN_OFF could test reforce.
>
> Final exit:
>
>     same gen=153
>     session_epoch_ended epoch=2
>     DOZE_UNFORCE_FINISHED exit=0
>     final package unsuspend success
>     sensor restore success
>
> So already PASS:
>
>     fresh entry
>     locked SCREEN_ON physical release
>     same logical epoch
>     same package generation
>     USER_PRESENT final exit
>
> Still NOT directly tested:
>
>     lockscreen visible
>     -> screen times out OFF while still locked
>     -> same-session genuine owned reforce
>
> That test was paused when the fresh late-settle orphan bug was discovered.
>
> ============================================================
> OWNED-SESSION REFORCE — 70ac1a2
> ============================================================
>
> Commit:
>
>     70ac1a20111cb379f64ece605ab0939f4ef3e536
>
> This protects async owned-session reforce against its session ending
> while the force command is in flight.
>
> It has:
>
>     durable ownedReforcePending marker
>     owned reforce phases
>     epoch identity
>     conservative stale cleanup
>     no fallback for NEW Doze
>     recovery-first handling
>
> Do not redesign it during the current test unless evidence specifically
> shows a problem.
>
> ============================================================
> FORK FEATURES THAT MUST BE PRESERVED
> ============================================================
>
> 1. Rebranding / dual install
>    - namespace base
>    - .fork application ID
>    - manifest ${applicationId}
>    - FGS specialUse
>    - shell grants and whitelist use dynamic package ID
>    - explicit prefs target .fork
>
> 2. Settings persistence
>    - SettingsActivity reload always notifies service
>    - execution mode committed before reload
>    - multiple Shizuku listeners
>    - destructive preference writes removed
>
> 3. SettingsBackup.java
>    - SAF Create/Open
>    - background import/export
>    - one reload after import
>
> 4. Multi-select package chooser
>    - search
>    - system/user filter
>    - select all
>    - batch result
>    - labels background
>    - icons lazy
>    - BlockApps batch commits once
>
> 5. DozeStateStore durable state
>    - private prefs: enforcedoze_doze_state
>    - pre/applied keys for airplane/BT/GPS/WiFi/data/battery saver/sensors/
>      biometrics/motion/hotspot
>    - inDoze
>    - synchronous commit
>    - package generation/session ownership
>    - entryPending
>    - ownedReforcePending
>
> 6. BootCompleteReceiver recovery
>    - device-state pending
>    - package-only pending
>    - fresh PREPARING recovery
>    - owned-reforce recovery
>
> 7. ForceDoze fixes
>    - fast getDeviceIdleState
>    - package shell batch/loop
>    - durable state journal
>    - Shizuku fixes
>
> 8. ShizukuHandler
>    - binder listeners
>    - ~2s wait
>    - multiple listeners
>    - reflective Shizuku.newProcess
>    - per-command Java thread/process
>
> ============================================================
> KNOWN DEFERRED ISSUES — DO NOT RANDOMLY MIX INTO CURRENT FIX
> ============================================================
>
> Deferred separately:
>
> - final handleScreenOn/session-exit barrier cleanup
> - notification shell disable/enable race
> - general device-state command reorder
> - sensor serializer narrow concurrency
> - generic generation races
> - generic SharedPreferences commit results
> - maintenance async restore/reapply
> - maintenance process death
> - Shizuku recovered ACTIVE physical leaveDoze durability
> - Shizuku newProcess deprecation/API14
> - stdout/stderr pipe deadlock
> - onDestroy async restore/boot timing
> - notification blocklist exact-set
> - biometric pre-state assumed
> - root orphan
> - getPlayingPackageName missing callback
> - root child survival
> - pre-N tracked release protocol absent
> - tunable callback absent
> - marker-stuck edge cases
> - PREPARING phantom boot risk
>
> Do not expand the current change into these.
>
> ============================================================
> CURRENT BUILD SITUATION
> ============================================================
>
> After committing dd3ded4, I ran:
>
>     .\gradlew.bat assembleDebug
>
> It failed BEFORE compilation because Gradle used:
>
>     C:\Program Files (x86)\Java\jre1.8.0_421\bin\java.exe
>
> Error:
>
>     Could not reserve enough space for 2097152KB object heap
>
> This is the already-known Windows 32-bit Java environment problem, NOT
> yet evidence of a source-code/build failure.
>
> The intended fix is to use Android Studio's 64-bit JBR for this
> PowerShell session, not change Windows globally.
>
> Expected Android Studio JBR path:
>
>     C:\Program Files\Android\Android Studio\jbr
>
> Previous ChatGPT had just asked me to check:
>
>     Test-Path "C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
>
> and, if True:
>
>     & "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" -version
>
> The new chat should continue from build-environment verification.
>
> ============================================================
> WHAT I WILL PASTE NEXT
> ============================================================
>
> I will paste the outputs of these four commands:
>
> 1.
>
>     java -version
>
> 2.
>
>     .\gradlew.bat --version
>
> 3.
>
>     .\gradlew.bat assembleDebug
>
> 4.
>
>     git status --short
>
> Interpret them carefully.
>
> If Gradle is still using 32-bit Java, guide me to set temporary
> PowerShell-session JAVA_HOME/PATH to:
>
>     C:\Program Files\Android\Android Studio\jbr
>
> without making a permanent/global Windows change.
>
> Likely temporary PowerShell form, but verify based on the outputs:
>
>     $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
>     $env:Path = "$env:JAVA_HOME\bin;$env:Path"
>
> Then verify:
>
>     java -version
>     .\gradlew.bat --version
>
> before rebuilding.
>
> Do not amend or recreate dd3ded4 merely because the first build used
> the wrong JVM.
>
> ============================================================
> WHAT HAPPENS AFTER BUILD PASS
> ============================================================
>
> Once the COMMITTED TREE build passes:
>
> 1. Verify commit still:
>
>        dd3ded441cf1dfc9277e23ec165a99c8981ab780
>
> 2. Verify parent still:
>
>        a9a1227c4ff2522af345340b929e0f79325944ee
>
> 3. Verify only review .diff files are untracked, unless Gradle created
>    something like:
>
>        .kotlin/
>
>    If .kotlin/ appears, remove the generated folder rather than changing
>    .gitignore unless there is a concrete reason.
>
> 4. Do NOT push yet.
>
> 5. Then guide installation of the newly built debug APK on:
>
>        30.30.30.234:37159
>
> 6. Preserve app data/settings using:
>
>        adb.exe -s 30.30.30.234:37159 install -r "D:\AndroidProjects\EnforceDoze\app\build\outputs\apk\debug\app-debug.apk"
>
> 7. Before testing, establish a clean baseline:
>
>        phone unlocked
>        home screen visible
>        screen ON
>
>    and inspect:
>
>        mForceIdle
>        mState
>        mLightState
>
>    plus:
>
>        inDoze
>        entryPending
>        ownedReforcePending
>
> Expected clean baseline:
>
>        mForceIdle=false
>        mState=ACTIVE
>        mLightState=ACTIVE
>
>        inDoze=false
>        entryPending=false
>        ownedReforcePending=false
>
> If not clean, STOP and investigate before screen-off testing.
>
> ============================================================
> REAL-DEVICE TEST PRIORITY AFTER BUILD
> ============================================================
>
> First priority is to prove dd3ded4 fixes the fresh late-settle bug.
>
> We previously saw:
>
>     controller SUCCESS text
>     immediate idle=false
>     ~47 ms later deepIdle=true
>
> The desired new behavior is:
>
>     force_idle_result
>         verdict=accepted_pending_confirmation
>         controllerResult=success
>         idleMode=false
>
> then:
>
>     entry_awaiting_idle_confirmation
>
> then later deep-idle broadcast:
>
>     entry_committed
>         confirmedBy=idle_broadcast
>
> and ONLY THEN:
>
>     logical session
>     epoch
>     ENTER
>     package generation/restrictions
>
> At no point should we again see:
>
>     semantic_rejection
>
> for controller SUCCESS text merely because the immediate PM sample is
> false.
>
> And at no point should we end up:
>
>     inDoze=false
>     entryPending=false
>     ownedReforcePending=false
>
> while:
>
>     mForceIdle=true
>
> That combination is the orphan regression and is a blocker.
>
> ============================================================
> SECOND REAL-DEVICE TEST AFTER FRESH LATE-SETTLE PASS
> ============================================================
>
> Resume the paused a9a1227 same-session lockscreen reforce test.
>
> Sequence:
>
> A. Clean unlocked baseline, screen ON.
>
> B. Turn screen OFF.
>    Wait about 15 sec.
>    Confirm fresh owned Doze session.
>
> C. Press power once.
>    Show lock screen.
>    DO NOT unlock.
>
> Expected while screen ON + still LOCKED:
>
>     mForceIdle=false
>     mState=ACTIVE
>     mLightState=ACTIVE
>
> or equivalent physically unforced state.
>
> Diagnostics should show:
>
>     lockscreen_release_start
>     lockscreen_unforce exit=0
>     lockscreen_release_result idleMode=false lifecycle=locked_wake
>
> Same logical epoch and same package generation must remain.
>
> D. Do NOT unlock.
>
> Let lockscreen time itself out so screen becomes OFF while still locked.
> Wait about 15 sec.
>
> Then inspect:
>
>     dumpsys deviceidle
>
> Expected:
>
>     mForceIdle=true
>     mState=IDLE
>     mLightState=OVERRIDE
>
> This must now be a genuine same-session owned reforce through the
> 70ac1a2 mechanism.
>
> Diagnostics should confirm:
>
>     same epoch
>     same package generation
>     package re-suspend same generation
>     owned reforce transaction
>     no new ENTER
>     no EXIT
>     no new package generation
>     no orphan marker debt
>
> E. Only after inspecting that state should USER_PRESENT/unlock finalize
> the session.
>
> Expected final:
>
>     one EXIT
>     epoch ended once
>     final package restore
>     sensor/biometric/etc. restore
>     physical unforce
>     all durable markers false
>
> ============================================================
> IMPORTANT DEVICE TEST RULE
> ============================================================
>
> Guide me ONE TEST STAGE AT A TIME.
>
> Do not dump a huge set of ADB commands at once.
>
> I am a non-developer and want to paste each output before moving to the
> next stage.
>
> ============================================================
> CURRENT DECISION STATUS
> ============================================================
>
> Latest source review for:
>
>     dd3ded4
>
> was:
>
>     SOURCE REVIEW PASS
>     COMMIT PASS
>
> Commit exists and is correctly based on a9a1227.
>
> Only remaining immediate requirement:
>
>     COMMITTED-TREE BUILD PASS using correct 64-bit Java
>
> Then:
>
>     REAL-DEVICE TEST
>
> No push yet.
> No merge.
> No master changes.
>
> ============================================================
> CONTINUE FROM HERE
> ============================================================
>
> I am now going to paste the outputs of:
>
> 1. java -version
> 2. .\gradlew.bat --version
> 3. .\gradlew.bat assembleDebug
> 4. git status --short
>
> Please analyze those outputs and continue from exactly this point.
> Do not restart the project explanation.
> Do not ask me to repeat prior history.

---

# PART IV — FINAL CONTINUATION PROMPT

Copy/paste or point a new ChatGPT conversation to this section.

## CONTINUE ENFORCEDOZE FROM HERE

I am continuing a long-running Android **EnforceDoze fork** reliability/correctness/hardening and public-release project.

`PROJECT_CONTINUATION.md` is the authoritative repository handoff. PART I and this FINAL CONTINUATION PROMPT supersede older historical snapshots in this file.

Do not make me restate completed history and do not reopen closed work without new evidence.

### Repository / workflow

- Fork: `rmpsdroid/EnforceDoze`
- Local repo: `D:\AndroidProjects\EnforceDoze`
- Application ID: `com.akylas.enforcedoze.fork`
- Java namespace: `com.akylas.enforcedoze`
- Current feature branch for Checkpoint A: `fix/shizuku-userservice-v1`
- Checkpoint A functional commit:
  `58c726714d17b40c5cb18a48cc67aec82cff7998`
- Last known integrated `master` baseline before Checkpoint A:
  `7ffeed4ed218ec29b18d123288c728b99191bcbb`

Always verify Git refs read-only when reopening; a later documentation commit, merge, or push may have advanced the branch after this text was written.

### Approval gates

The user requires exact, separate approvals:

- functional/documentation commit: exact `approve commit`
- feature push: exact `approve push`
- merge to master: exact `approve merge to master`
- master push: exact `approve push master`

Never infer one approval from another.
Never use `git add .`.
Stage only explicitly reviewed production/documentation files.
Protected untracked audit/test evidence must not be deleted, cleaned, overwritten, staged, or committed casually.

### Shell preference

Use **PowerShell** for repository work and for ADB/device command sequences.

Repo prompt:

`PS D:\AndroidProjects\EnforceDoze>`

Use ADB explicitly as:

`C:\adb\adb.exe`

For device test workflows, PowerShell blocks are preferred over multi-command CMD pastes.

Do not use `monkey`.

### Devices

Primary:
- Samsung S26 Ultra, API 36
- Shizuku, no root
- normal-use validation device
- wireless ADB port is dynamic; always verify current serial

Synthetic/spare:
- Samsung M30, Android 10 / API 29
- ADB serial `30.30.30.70:5555`
- Shizuku, no root
- safe device for controlled synthetic runtime testing
- Spotify package `com.spotify.music`; do not uninstall it

### Closed baseline before public-beta blocker work

Closed and integrated before Checkpoint A include:

- R0-1 dead/unreachable `leaveDoze`: PASS / no change
- R0-3 legacy final-exit ownership
- R0-4 media callback completion
- R0-5 root child/orphan physical DeviceIdle serialization
- R0-6 durable state-journal commit handling
- maintenance async restore/reapply
- maintenance process-death recovery

Do not reopen these without new evidence.

### CHECKPOINT A - SHIZUKU USERSERVICE MODERNIZATION

Branch:

`fix/shizuku-userservice-v1`

Functional commit:

`58c726714d17b40c5cb18a48cc67aec82cff7998`

Subject:

`Migrate Shizuku backend to UserService`

Production files:

- `app/build.gradle`
- `app/src/main/aidl/com/akylas/enforcedoze/IShizukuCommandService.aidl`
- `app/src/main/java/com/akylas/enforcedoze/ShizukuCommandService.java`
- `app/src/main/java/com/akylas/enforcedoze/ShizukuHandler.java`

Final architecture:

- deprecated `Shizuku.newProcess()` / `ShizukuRemoteProcess` removed from the migrated backend;
- command execution moved to Shizuku UserService through AIDL;
- UserService is `daemon(false)`;
- stdout/stderr are drained concurrently;
- app-facing async callback behavior is preserved;
- commands are not automatically retried;
- Root mode is unchanged;
- each privileged command runs in its own `setsid` process group;
- UserService teardown sends group-wide `SIGKILL`, preventing privileged descendants from surviving caller death.

Candidate 1 is intentionally superseded:

- app death removed the old UserService and tracked shell;
- a spawned `sleep` child survived;
- Candidate 1 therefore failed the active-child orphan gate and was not committed.

Candidate 2 final runtime gates:

- build PASS
- UserService bind / ordinary command PASS
- real force-idle PASS
- Battery Saver / motion privileged commands PASS
- app process death -> old UserService death PASS
- app restart -> new UserService PASS
- kill only UserService -> reconnect with same app process PASS
- active-child orphan test PASS
- final process-group cleanup log observed
- `1000` stdout + `1000` stderr pipe regression PASS

Validated Candidate 2 APK:

`shizuku-userservice-candidate2-debug.apk`

SHA-256:

`C0D2E3366DEB7897BD7F5A30A433A22E50B0FE2B81EBFF78E29FC49BB5077614`

Final review artifact:

`shizuku-userservice-candidate2-final-review.txt`

SHA-256:

`17B6A428264705DB173200D1777A3AAD8E05D6310F370450696EA3BCCF3D101F`

Checkpoint A functional implementation is **PASS / COMMITTED**.
Documentation commit, feature push, merge, and master push remain independently approval-controlled until Git proves otherwise.

### NEXT - CHECKPOINT B: PUBLIC-BETA STATE INTEGRITY

After Checkpoint A is integrated, create:

`fix/public-beta-state-integrity-v1`

Bundle these runtime/state-integrity items:

1. notification exact-set ownership/generation;
2. notification process-death/boot recovery;
3. biometric real pre-state;
4. `setInDoze` durability/lifecycle;
5. raise `minSdkVersion 23` to `24`.

Required design direction:

- notification blocklist must persist the exact package restore set with monotonic generation;
- preserve prior debt and use generation-safe compare-and-clear;
- boot/process recreation must restore notification-only debt;
- biometric entry must read the real `Settings.Secure biometric_keyguard_enabled` pre-state:
  - false -> no claim/apply;
  - unknown -> skip rather than guessing true;
- `setInDoze` durable writes must not silently report ownership state that was not committed;
- min SDK becomes 24 for the public-beta line.

Then perform one consolidated:
review -> build -> M30 synthetic validation -> S26 normal-use validation -> freeze.

### Later/deferred after Checkpoint B

Unless new evidence changes priority:

- tunable callback absence
- marker-stuck recovery
- PREPARING phantom boot / stale-session behavior

Also complete the pre-beta exported-component / Tasker security pass without breaking intentional automation.

### Public release / rebranding after runtime blockers

Once Checkpoint B and the short security/release pass are complete, proceed to:

- app name / icon / branding strategy
- Material 3 UI redesign
- dashboard/status cards
- onboarding
- settings cleanup
- About/licenses
- release versioning/signing
- install/upgrade compatibility
- release APK hashes
- polished GitHub README
- screenshots
- Shizuku/root setup documentation
- attribution/upstream licensing
- GitHub release preparation

Favor consolidated checkpoints over endless micro-audits:

`understand complete issue -> consolidated correction -> review -> build -> runtime test -> move on`

# END OF AUTHORITATIVE CONTINUATION FILE