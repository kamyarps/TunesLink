package com.kamyarps.tuneslink

/**
 * Layout-only navigation helpers. The phone and tablet layouts present the same stored navigation
 * state; switching between them (for example, rotating a phone into the landscape workspace) must
 * never rewrite where the user is.
 */

private const val KEY_WORKSPACE_AUTO_OPENED = "workspace_auto_opened_kind"

/**
 * The tablet workspace always shows a library category. When the stored browse state has none,
 * open Albums and remember that the workspace chose it, so the phone layout can restore the
 * Library root it was actually on. The stored destination is left untouched.
 */
internal fun TunesLinkViewModel.openWorkspaceDefaultKind() {
    if (mutableState.value.browse.kind != null) return
    openLibraryKind(LibraryBrowseKind.Albums)
    savedStateHandle[KEY_WORKSPACE_AUTO_OPENED] = true
}

/** The user chose where to go in the workspace; its default category is now theirs to keep. */
internal fun TunesLinkViewModel.claimWorkspaceBrowse() {
    if (savedStateHandle.get<Boolean>(KEY_WORKSPACE_AUTO_OPENED) == true) {
        savedStateHandle[KEY_WORKSPACE_AUTO_OPENED] = false
    }
}

/**
 * Called when the phone layout is shown. If the workspace opened its default category and the
 * user has not navigated since, return to the Library root the phone layout was showing.
 */
internal fun TunesLinkViewModel.restoreWorkspaceAutoOpenedRoot() {
    if (savedStateHandle.get<Boolean>(KEY_WORKSPACE_AUTO_OPENED) != true) return
    savedStateHandle[KEY_WORKSPACE_AUTO_OPENED] = false
    if (mutableState.value.browse.isWorkspaceDefault()) navigateUpLibrary()
}

internal fun LibraryBrowseUiState.isWorkspaceDefault(): Boolean =
    kind == LibraryBrowseKind.Albums && selectedCollection == null &&
        albumParent == null && parentBrowse == null

/**
 * In the workspace a category is always visible, so a category itself is the root: Back only
 * goes up from an opened collection or a drilled-in artist/genre. At the root the system handles
 * Back (exit or predictive back-to-home).
 */
internal fun LibraryBrowseUiState.workspaceCanNavigateUp(): Boolean =
    selectedCollection != null || parentBrowse != null

/**
 * The destination whose Back behaviour applies. The workspace renders Now Playing as the library
 * workspace (transport lives in its header), so Back must behave as it does on Library.
 */
internal fun effectiveBackDestination(
    destination: TunesLinkDestination?,
    tabletWorkspace: Boolean,
): TunesLinkDestination? =
    if (tabletWorkspace && destination == TunesLinkDestination.NowPlaying) {
        TunesLinkDestination.Library
    } else {
        destination
    }

/** Whether Back should go up the library hierarchy for the current layout. */
internal fun browseBackEnabled(
    destination: TunesLinkDestination?,
    browse: LibraryBrowseUiState,
    tabletWorkspace: Boolean,
): Boolean = destination == TunesLinkDestination.Library && if (tabletWorkspace) {
    browse.workspaceCanNavigateUp()
} else {
    browse.canNavigateUp
}
