# Workspace switching: reliable drawer selection

Task: [Fix workspace switching bug](https://ticktick.com/webapp/#p/61e00c6c8f0828657f6c9bd4/tasks/6acc8bee8f08b838e30999bd)

## Finding

The drawer kept the selected workspace outside the list submitted to `AsyncListDiffer`.
It refreshed selection styling using a list commit callback. Superseding a pending
diff discards that callback. In particular, the activity observes both the active
workspace and the workspace list: a second emission with the same selection can
supersede the selection-changing diff without scheduling its own highlight refresh.
The stored workspace can switch while the drawer still highlights the old row.

This is a reproducible UI race. It does not establish whether every reported
workspace-switching failure has the same cause; stale user history has not been
reproduced on a device.

## Implementation plan

1. Submit immutable rows containing both the workspace and its selected state.
2. Include selection in content comparisons while preserving workspace IDs as row
   identity. Remove the separate mutable selection and commit callback refresh.
3. Bind visual and accessibility selection from the committed row. Keep workspace
   selection and long-press actions tied to that row's workspace.
4. Use controlled diff executors to test overlapping observer emissions and rapid
   switches/reordering. Run existing ViewModel/store tests and build a debug APK.

## Automated validation

Passed `:app:testDebugUnitTest` filtered to `DrawerProjectsAdapterTest`,
`HomeViewModelTest`, and `FileConversationStoreTest`, together with `assembleDebug`.
`git diff --check` also passed.

## Device verification

Switch between two populated workspaces, reopening the drawer after each switch.
Confirm that the highlighted workspace, chat list, and open conversation agree.
Repeat quickly and after restarting the app. If an older vault still fails, capture
the storage error and investigate that case separately.
