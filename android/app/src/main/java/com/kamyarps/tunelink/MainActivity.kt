package com.kamyarps.tuneslink

import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.get
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

private const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

internal enum class RecoveryDialogAction { Dismiss, Primary, ChooseAnother }

class MainActivity : ComponentActivity() {
    private val viewModel: TunesLinkViewModel by viewModels()
    private var animationsEnabled by mutableStateOf(true)
    private var highContrastEnabled by mutableStateOf(false)
    private val localNetworkPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // API 37 preview builds can deliver the activity-result boolean before
        // PackageManager's final local-network grant has propagated. Treat the
        // authoritative permission state as granted when either source agrees.
        viewModel.localNetworkPermissionResult(granted || hasLocalNetworkAccess())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        animationsEnabled = ValueAnimator.areAnimatorsEnabled()
        highContrastEnabled = isHighContrastEnabled()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb()),
            navigationBarStyle = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb()),
        )
        setContent {
            TunesLinkDesignTheme(
                highContrastEnabled = highContrastEnabled,
                animationsEnabled = animationsEnabled,
            ) {
                TunesLinkApp(
                    viewModel = viewModel,
                    hasLocalNetworkAccess = ::hasLocalNetworkAccess,
                    requestLocalNetworkPermission = {
                        if (LocalNetworkAccessPolicy.isRuntimePermissionRequired(
                                Build.VERSION.SDK_INT,
                                applicationInfo.targetSdkVersion,
                            )
                        ) {
                            localNetworkPermission.launch(ACCESS_LOCAL_NETWORK_PERMISSION)
                        } else {
                            viewModel.localNetworkPermissionResult(true)
                        }
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (viewModel.ensureLocalNetworkPermission(hasLocalNetworkAccess())) {
            viewModel.onForeground()
        }
    }

    override fun onResume() {
        super.onResume()
        animationsEnabled = ValueAnimator.areAnimatorsEnabled()
        highContrastEnabled = isHighContrastEnabled()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) highContrastEnabled = isHighContrastEnabled()
    }

    override fun onStop() {
        if (!isChangingConfigurations) viewModel.onBackground()
        super.onStop()
    }

    private fun hasLocalNetworkAccess(): Boolean = LocalNetworkAccessPolicy.hasAccess(
        Build.VERSION.SDK_INT,
        applicationInfo.targetSdkVersion,
        ContextCompat.checkSelfPermission(this, ACCESS_LOCAL_NETWORK_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED,
    )

    private fun isHighContrastEnabled(): Boolean = Settings.Secure.getInt(
        contentResolver,
        "high_text_contrast_enabled",
        0,
    ) == 1
}

@Composable
private fun TunesLinkApp(
    viewModel: TunesLinkViewModel,
    hasLocalNetworkAccess: () -> Boolean,
    requestLocalNetworkPermission: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val windowWidth = with(density) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val useTabletWorkspace = windowWidth >= 600.dp && density.fontScale < 1.5f
    val useNavigationRail = windowWidth >= 600.dp && !useTabletWorkspace
    val haptic = LocalHapticFeedback.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val appFocusRequester = remember { FocusRequester() }
    val availability = ConnectionAvailability.from(state.connection)
    var dismissedRecoveryKind by remember { mutableStateOf<ConnectionAvailabilityKind?>(null) }
    var recoveryAction by remember(availability.kind) {
        mutableStateOf<RecoveryDialogAction?>(null)
    }
    LaunchedEffect(availability.kind) {
        if (!availability.visible || dismissedRecoveryKind != availability.kind) {
            dismissedRecoveryKind = null
        }
    }
    val showRecoveryDialog = shouldPresentConnectionRecoveryDialog(
        availability = availability,
        dismissedKind = dismissedRecoveryKind,
        hasModal = state.modal != null,
        connectedRoute = state.route is TunesLinkRoute.Connected,
    )
    val requestRecoveryAction: (RecoveryDialogAction) -> Unit = { action ->
        if (recoveryAction == null) recoveryAction = action
    }
    val modalVisible = state.modal != null || showRecoveryDialog
    val modalReplacementPending = state.modalPresentation?.replacement != null
    val modalDismissing = state.modalPresentation?.dismissRequested == true &&
        !modalReplacementPending
    val recoveryDismissing = showRecoveryDialog && recoveryAction != null
    val backgroundShouldBlur = modalVisible && !modalDismissing && !recoveryDismissing
    val backgroundBlurRadius by animateDpAsState(
        targetValue = if (backgroundShouldBlur) 12.dp else 0.dp,
        animationSpec = tween(
            durationMillis = if (backgroundShouldBlur) {
                TunesLinkMotion.ModalEnter
            } else {
                TunesLinkMotion.ModalExit
            },
            easing = TunesLinkMotion.EaseOut,
        ),
        label = "Modal background blur",
    )
    var modalWasVisible by remember { mutableStateOf(false) }

    LaunchedEffect(modalVisible) {
        when {
            modalVisible && !modalWasVisible -> appFocusRequester.saveFocusedChild()
            !modalVisible && modalWasVisible -> appFocusRequester.restoreFocusedChild()
        }
        modalWasVisible = modalVisible
    }

    LaunchedEffect(state.announcement?.id) {
        val announcement = state.announcement ?: return@LaunchedEffect
        when (announcement.hapticIntent) {
            HapticIntent.Confirm -> haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            HapticIntent.Reject -> haptic.performHapticFeedback(HapticFeedbackType.Reject)
            HapticIntent.None -> Unit
        }
        delay(1_500)
        viewModel.consumeAnnouncement(announcement.id)
    }

    val connectedDestination = (state.route as? TunesLinkRoute.Connected)?.destination
    val backHandled = !imeVisible && (state.modal != null || showRecoveryDialog ||
        state.browse.canNavigateUp || state.library.searchActive ||
        state.library.editingQuery.isNotEmpty() || connectedDestination == TunesLinkDestination.NowPlaying)
    TunesLinkSharedTransitionRoot {
        BackHandler(enabled = backHandled) {
        if (showRecoveryDialog) {
            requestRecoveryAction(RecoveryDialogAction.Dismiss)
        } else if (connectedDestination == TunesLinkDestination.Library && state.browse.canNavigateUp) {
            viewModel.navigateUpLibrary()
        } else {
            when (navigationBackAction(
                hasModal = state.modal != null,
                searchActive = state.library.searchActive || state.library.editingQuery.isNotEmpty(),
                destination = connectedDestination,
            )) {
                NavigationBackAction.DismissModal -> viewModel.requestModalDismiss()
                NavigationBackAction.CancelSearch -> viewModel.cancelSearch()
                NavigationBackAction.ShowLibrary -> viewModel.navigate(TunesLinkDestination.Library)
                NavigationBackAction.System -> Unit
            }
        }
    }
    val routeTransitionDuration = if (TunesLinkTheme.motion.spatialEnabled) {
        TunesLinkMotion.StatusCrossfade
    } else {
        TunesLinkMotion.ReducedMotionFade
    }
    TunesLinkScaffold(
        modifier = Modifier
            .focusRequester(appFocusRequester)
            .focusGroup()
            .blur(backgroundBlurRadius),
        bottomBar = {
            val route = state.route as? TunesLinkRoute.Connected
            if (route != null && !imeVisible && !useTabletWorkspace) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(TunesLinkTheme.colors.surface.copy(alpha = 0.91f)),
                ) {
                    if (useNavigationRail) Spacer(Modifier.width(TunesLinkSizes.navigationRailWidth))
                    UnifiedPlayerBar(
                        player = state.player,
                        destination = route.destination,
                        onOpenPlayer = { viewModel.navigate(TunesLinkDestination.NowPlaying) },
                        onLibrary = { viewModel.navigate(TunesLinkDestination.Library) },
                        onPlayer = { viewModel.navigate(TunesLinkDestination.NowPlaying) },
                        onSearch = { viewModel.navigate(TunesLinkDestination.Search) },
                        onPlayPause = viewModel::togglePlayback,
                        showMiniPlayer = route.destination != TunesLinkDestination.NowPlaying,
                        controlsEnabled = ConnectionAvailability.from(state.connection).controlsEnabled,
                        showNavigation = !useNavigationRail,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) { scaffoldPadding ->
        AnimatedContent(
            targetState = state.route,
            transitionSpec = {
                fadeIn(
                    androidx.compose.animation.core.tween(
                        routeTransitionDuration,
                        easing = TunesLinkMotion.EaseOut,
                    ),
                ) togetherWith fadeOut(
                    androidx.compose.animation.core.tween(
                        routeTransitionDuration,
                        easing = TunesLinkMotion.EaseOut,
                    ),
                )
            },
            contentKey = TunesLinkRoute::rootContentKey,
            label = "Root destination",
            modifier = Modifier.fillMaxSize(),
        ) { route ->
            when (route) {
                TunesLinkRoute.Welcome -> WelcomeScreen(
                    state = state,
                    padding = scaffoldPadding,
                    onDiscover = { viewModel.requestDiscovery(hasLocalNetworkAccess()) },
                    onManual = { viewModel.requestManualAddress(hasLocalNetworkAccess()) },
                    onChooseBridge = viewModel::chooseBridge,
                    onRetryRevocations = {
                        viewModel.requestRetryPendingRevocations(hasLocalNetworkAccess())
                    },
                )
                TunesLinkRoute.LocalNetworkPermission -> PermissionScreen(
                    padding = scaffoldPadding,
                    onContinue = requestLocalNetworkPermission,
                )
                TunesLinkRoute.Connecting -> ConnectingScreen(state.bridgeName, scaffoldPadding)
                is TunesLinkRoute.Connected -> ConnectedScreen(
                    state = state,
                    destination = route.destination,
                    padding = scaffoldPadding,
                    viewModel = viewModel,
                    showNavigationRail = useNavigationRail,
                    showTabletWorkspace = useTabletWorkspace,
                    animatedVisibilityScope = this,
                )
            }
        }
        state.announcement?.let { announcement ->
            val announcementText = if (announcement.quantity != null) {
                pluralStringResource(
                    announcement.messageRes,
                    announcement.quantity,
                    *announcement.arguments.toTypedArray(),
                )
            } else {
                stringResource(announcement.messageRes, *announcement.arguments.toTypedArray())
            }
            Text(
                announcementText,
                modifier = Modifier
                    .size(1.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = Color.Transparent,
                fontSize = 1.sp,
            )
        }
    }

        TunesLinkModalHost(state, viewModel)
        if (showRecoveryDialog) {
        ConnectionRecoveryDialog(
            availability = availability,
            dismissRequested = recoveryAction != null,
            onDismiss = { requestRecoveryAction(RecoveryDialogAction.Dismiss) },
            onPrimary = { requestRecoveryAction(RecoveryDialogAction.Primary) },
            onChooseAnother = { requestRecoveryAction(RecoveryDialogAction.ChooseAnother) },
            onDismissComplete = {
                val completedAction = recoveryAction ?: RecoveryDialogAction.Dismiss
                val completedKind = availability.kind
                dismissedRecoveryKind = completedKind
                recoveryAction = null
                when (completedAction) {
                    RecoveryDialogAction.Dismiss -> Unit
                    RecoveryDialogAction.Primary -> when (completedKind) {
                        ConnectionAvailabilityKind.ConnectionLost -> viewModel.tryAgain()
                        ConnectionAvailabilityKind.PairingExpired,
                        ConnectionAvailabilityKind.IdentityChanged,
                        -> viewModel.pairAgain()
                        else -> Unit
                    }
                    RecoveryDialogAction.ChooseAnother -> viewModel.chooseAnotherComputer()
                }
            },
        )
        }
    }
}

@Composable
private fun WelcomeScreen(
    state: TunesLinkUiState,
    padding: PaddingValues,
    onDiscover: () -> Unit,
    onManual: () -> Unit,
    onChooseBridge: (BridgeClient.BridgeInfo) -> Unit,
    onRetryRevocations: () -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        val compactHeight = maxHeight < 680.dp
        val wideContent = maxWidth > 500.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (maxWidth >= 600.dp) 32.dp else 24.dp,
                    vertical = if (compactHeight) 16.dp else 24.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.tunelink_brand_app_icon),
                contentDescription = null,
                modifier = Modifier.size(if (compactHeight) 56.dp else 84.dp),
            )
            Spacer(Modifier.height(if (compactHeight) 16.dp else 32.dp))
            Text(
                stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.displayLarge,
                color = TunesLinkTheme.colors.primaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.welcome_detail),
                style = MaterialTheme.typography.bodyLarge,
                color = TunesLinkTheme.colors.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            )
            Spacer(Modifier.height(if (compactHeight) 16.dp else 36.dp))
            val discoveryLabel = stringResource(
                if (state.connection is ConnectionState.Discovering) {
                    R.string.finding_computers
                } else {
                    R.string.find_computer
                },
            )
            if (compactHeight && wideContent) {
                Row(
                    modifier = Modifier.widthIn(max = 920.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TunesLinkPrimaryButton(
                        label = discoveryLabel,
                        onClick = onDiscover,
                        enabled = state.connection !is ConnectionState.Discovering,
                        icon = Icons.Rounded.Search,
                        modifier = Modifier.weight(1f),
                    )
                    TunesLinkTonalAction(
                        stringResource(R.string.enter_address),
                        onManual,
                        Modifier.weight(1f),
                        Icons.Rounded.Computer,
                    )
                }
            } else {
                TunesLinkPrimaryButton(
                    label = discoveryLabel,
                    onClick = onDiscover,
                    enabled = state.connection !is ConnectionState.Discovering,
                    icon = Icons.Rounded.Search,
                    modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TunesLinkTonalAction(
                    stringResource(R.string.enter_address),
                    onManual,
                    Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                    Icons.Rounded.Computer,
                )
            }
            if (state.discoveryError != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    state.discoveryError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TunesLinkTheme.colors.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (state.pendingRevocationCount > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.pending_revocation_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TunesLinkTheme.colors.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                TunesLinkTonalAction(
                    label = stringResource(R.string.retry),
                    onClick = onRetryRevocations,
                    modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                    enabled = !state.revocationRetryBusy,
                )
            }
            if (state.discovered.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    stringResource(R.string.choose_computer),
                    style = MaterialTheme.typography.titleLarge,
                    color = TunesLinkTheme.colors.primaryText,
                )
                Spacer(Modifier.height(8.dp))
                state.discovered.forEach { bridge ->
                    ComputerChoice(bridge, onChooseBridge)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun ComputerChoice(
    bridge: BridgeClient.BridgeInfo,
    onChoose: (BridgeClient.BridgeInfo) -> Unit,
) {
    Row(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { onChoose(bridge) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Computer, contentDescription = null, tint = TunesLinkTheme.colors.secondaryText)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(bridge.name, style = MaterialTheme.typography.bodyLarge, color = TunesLinkTheme.colors.primaryText)
            Text("${bridge.host}:${bridge.port}", style = MaterialTheme.typography.bodyMedium, color = TunesLinkTheme.colors.secondaryText)
        }
    }
}

@Composable
private fun PermissionScreen(padding: PaddingValues, onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.Lock,
            contentDescription = null,
            tint = TunesLinkTheme.colors.accentText,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.local_network_title),
            style = MaterialTheme.typography.headlineLarge,
            color = TunesLinkTheme.colors.primaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.local_network_detail),
            style = MaterialTheme.typography.bodyLarge,
            color = TunesLinkTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        TunesLinkPrimaryButton(stringResource(R.string.continue_label), onContinue, Modifier.widthIn(max = 460.dp).fillMaxWidth())
    }
}

@Composable
private fun ConnectingScreen(computer: String, padding: PaddingValues) {
    ContentState(
        title = if (computer.isBlank()) stringResource(R.string.connecting) else stringResource(R.string.connecting_to, computer),
        detail = stringResource(R.string.connecting_detail),
        loading = true,
        modifier = Modifier.fillMaxSize().padding(padding),
    )
}

@Composable
private fun ConnectedScreen(
    state: TunesLinkUiState,
    destination: TunesLinkDestination,
    padding: PaddingValues,
    viewModel: TunesLinkViewModel,
    showNavigationRail: Boolean,
    showTabletWorkspace: Boolean,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
) {
    if (showTabletWorkspace) {
        TabletTunesWorkspace(
            state = state,
            destination = destination,
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        if (showNavigationRail) {
            TunesLinkDestinationRail(
                destination = destination,
                onLibrary = { viewModel.navigate(TunesLinkDestination.Library) },
                onPlayer = { viewModel.navigate(TunesLinkDestination.NowPlaying) },
                onSearch = { viewModel.navigate(TunesLinkDestination.Search) },
                modifier = Modifier
                    .width(TunesLinkSizes.navigationRailWidth)
                    .fillMaxHeight(),
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (destination) {
                TunesLinkDestination.Library -> LibraryBrowseScreen(
                    state,
                    viewModel,
                    Modifier
                        .widthIn(max = TunesLinkSizes.readableContentMaxWidth)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .align(Alignment.Center),
                )
                TunesLinkDestination.NowPlaying -> NowPlayingScreen(
                    state,
                    viewModel,
                    Modifier.fillMaxSize(),
                    animatedVisibilityScope,
                )
                TunesLinkDestination.Search -> SearchScreen(
                    state,
                    viewModel,
                    Modifier
                        .widthIn(max = TunesLinkSizes.readableContentMaxWidth)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .align(Alignment.Center),
                )
            }
        }
    }
}
