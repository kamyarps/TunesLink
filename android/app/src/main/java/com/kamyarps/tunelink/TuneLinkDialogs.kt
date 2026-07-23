package com.kamyarps.tuneslink

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.get
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max


private const val DIALOG_DIM_AMOUNT = 0.32f

@Composable
internal fun TunesLinkModalHost(state: TunesLinkUiState, viewModel: TunesLinkViewModel) {
    val dismissRequested = state.modalPresentation?.dismissRequested == true
    when (val modal = state.modal) {
        null -> Unit
        TunesLinkModal.ManualAddress -> ManualAddressDialog(state, viewModel, dismissRequested)
        is TunesLinkModal.Pairing -> PairingDialog(
            state,
            modal.bridge,
            viewModel,
            dismissRequested,
        )
        TunesLinkModal.ConnectionDetails -> ConnectionDetailsDialog(state, viewModel, dismissRequested)
        TunesLinkModal.Privacy -> PrivacyDialog(viewModel, dismissRequested)
        TunesLinkModal.ForgetConfirmation -> ForgetDialog(state.bridgeName, viewModel, dismissRequested)
    }
}

@Composable
internal fun ConnectionRecoveryDialog(
    availability: ConnectionAvailability,
    dismissRequested: Boolean,
    onDismiss: () -> Unit,
    onPrimary: () -> Unit,
    onChooseAnother: () -> Unit,
    onDismissComplete: () -> Unit,
) {
    TunesLinkAlertDialog(
        onDismiss = onDismiss,
        dismissRequested = dismissRequested,
        onDismissComplete = onDismissComplete,
        title = stringResource(availability.titleRes),
        detail = stringResource(
            availability.detailRes,
            *availability.detailArguments.toTypedArray(),
        ),
        titleIcon = Icons.Rounded.ErrorOutline,
        titleIconTint = TunesLinkTheme.colors.danger,
        content = {
            TunesLinkTonalAction(
                label = stringResource(R.string.choose_another_computer),
                onClick = onChooseAnother,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Computer,
            )
        },
        confirmLabel = availability.primaryLabelRes?.let { stringResource(it) }
            ?: stringResource(R.string.done),
        onConfirm = onPrimary,
        dismissLabel = stringResource(R.string.not_now),
    )
}

@Composable
private fun ManualAddressDialog(state: TunesLinkUiState, viewModel: TunesLinkViewModel, dismissRequested: Boolean) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    TunesLinkAlertDialog(
        onDismiss = viewModel::closeModal,
        dismissRequested = dismissRequested,
        onDismissComplete = viewModel::completeModalDismiss,
        title = stringResource(R.string.manual_address_title),
        detail = stringResource(R.string.manual_address_detail),
        content = {
            OutlinedTextField(
                value = state.manualAddress,
                onValueChange = viewModel::updateManualAddress,
                label = { Text(stringResource(R.string.ipv4_address)) },
                placeholder = { Text("192.168.1.20") },
                supportingText = state.manualAddressError?.let { error -> { Text(error, color = TunesLinkTheme.colors.danger) } },
                isError = state.manualAddressError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    if (!state.manualResolutionBusy) viewModel.resolveManualAddress()
                }),
                colors = tunesLinkTextFieldColors(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
            )
        },
        confirmLabel = stringResource(
            if (state.manualResolutionBusy) R.string.connecting else R.string.connect,
        ),
        onConfirm = viewModel::resolveManualAddress,
        confirmEnabled = !state.manualResolutionBusy,
        dismissLabel = stringResource(R.string.cancel),
    )
}

@Composable
private fun PairingDialog(
    state: TunesLinkUiState,
    bridge: BridgeClient.BridgeInfo,
    viewModel: TunesLinkViewModel,
    dismissRequested: Boolean,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val submitPairing = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (state.pairing.canSubmit) viewModel.pair()
    }
    TunesLinkAlertDialog(
        onDismiss = viewModel::closeModal,
        dismissRequested = dismissRequested,
        onDismissComplete = viewModel::completeModalDismiss,
        title = stringResource(R.string.pair_with, bridge.name),
        detail = stringResource(R.string.pair_detail),
        dialogMaxWidth = 440.dp,
        content = {
            Column {
                OutlinedTextField(
                    value = state.pairing.code,
                    onValueChange = viewModel::updatePairingCode,
                    label = { Text(stringResource(R.string.six_digit_code)) },
                    placeholder = { Text("000000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitPairing() }),
                    singleLine = true,
                    isError = state.pairing.codeError != null,
                    supportingText = state.pairing.codeError?.let { error -> { Text(error, color = TunesLinkTheme.colors.danger) } },
                    colors = tunesLinkTextFieldColors(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmLabel = when (state.pairing.phase) {
            PairingPhase.Editing -> stringResource(R.string.pair_securely)
            PairingPhase.Submitting -> stringResource(R.string.pairing)
            PairingPhase.Success -> stringResource(R.string.connected)
        },
        confirmIcon = if (state.pairing.phase == PairingPhase.Success) Icons.Rounded.CheckCircle else null,
        onConfirm = submitPairing,
        confirmEnabled = state.pairing.canSubmit,
        dismissLabel = stringResource(R.string.cancel),
    )
}

@Composable
private fun ConnectionDetailsDialog(state: TunesLinkUiState, viewModel: TunesLinkViewModel, dismissRequested: Boolean) {
    val availability = ConnectionAvailability.from(state.connection)
    val pairingRequired = state.connection is ConnectionState.Unauthorized ||
        state.connection is ConnectionState.IdentityChanged
    TunesLinkAlertDialog(
        onDismiss = viewModel::closeModal,
        dismissRequested = dismissRequested,
        onDismissComplete = viewModel::completeModalDismiss,
        title = state.bridgeName.ifBlank { stringResource(R.string.connection_default) },
        detail = if (state.connection is ConnectionState.Connected) {
            stringResource(R.string.connection_detail)
        } else {
            stringResource(
                availability.detailRes,
                *availability.detailArguments.toTypedArray(),
            )
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow(stringResource(R.string.address), state.bridgeAddress)
                HorizontalDivider(color = TunesLinkTheme.colors.separator)
                TunesLinkTonalAction(stringResource(R.string.privacy), viewModel::showPrivacy, Modifier.fillMaxWidth(), Icons.Rounded.Lock)
                if (state.connection !is ConnectionState.Connected) {
                    TunesLinkTonalAction(
                        stringResource(if (pairingRequired) R.string.pair_again else R.string.reconnect),
                        if (pairingRequired) viewModel::pairAgain else viewModel::tryAgain,
                        Modifier.fillMaxWidth(),
                    )
                }
                HorizontalDivider(color = TunesLinkTheme.colors.separator)
                TunesLinkTonalAction(
                    stringResource(R.string.forget_computer),
                    viewModel::requestForget,
                    Modifier.fillMaxWidth(),
                    color = TunesLinkTheme.colors.danger,
                )
            }
        },
        confirmLabel = stringResource(R.string.done),
        onConfirm = viewModel::closeModal,
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = TunesLinkTheme.colors.secondaryText)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = TunesLinkTheme.colors.primaryText)
    }
}

@Composable
private fun PrivacyDialog(viewModel: TunesLinkViewModel, dismissRequested: Boolean) {
    TunesLinkAlertDialog(
        onDismiss = viewModel::closeModal,
        dismissRequested = dismissRequested,
        onDismissComplete = viewModel::completeModalDismiss,
        title = stringResource(R.string.privacy),
        detail = stringResource(R.string.privacy_detail),
        content = {
            Text(
                stringResource(R.string.privacy_body),
                style = MaterialTheme.typography.bodyLarge,
                color = TunesLinkTheme.colors.secondaryText,
            )
        },
        confirmLabel = stringResource(R.string.done),
        onConfirm = viewModel::closeModal,
    )
}

@Composable
private fun ForgetDialog(computer: String, viewModel: TunesLinkViewModel, dismissRequested: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TunesLinkAlertDialog(
        onDismiss = viewModel::closeModal,
        dismissRequested = dismissRequested,
        onDismissComplete = viewModel::completeModalDismiss,
        title = stringResource(R.string.forget_computer_title),
        detail = stringResource(
            R.string.forget_computer_detail,
            computer.ifBlank { stringResource(R.string.this_computer) },
        ),
        content = {
            state.forgetError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TunesLinkTheme.colors.danger,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        },
        confirmLabel = stringResource(
            if (state.forgetBusy) R.string.removing else R.string.forget_computer_confirm,
        ),
        onConfirm = viewModel::forget,
        confirmEnabled = !state.forgetBusy,
        dismissLabel = stringResource(R.string.cancel),
        destructive = true,
    )
}

@Composable
private fun TunesLinkAlertDialog(
    onDismiss: () -> Unit,
    dismissRequested: Boolean,
    onDismissComplete: () -> Unit,
    title: String,
    detail: String,
    dialogMaxWidth: Dp = 520.dp,
    titleIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    titleIconTint: Color? = null,
    content: @Composable () -> Unit,
    confirmLabel: String,
    confirmIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissLabel: String? = null,
    destructive: Boolean = false,
) {
    val motion = TunesLinkTheme.motion
    val opacity = remember { Animatable(0f) }
    val scale = remember { Animatable(if (motion.spatialEnabled) 0.97f else 1f) }
    LaunchedEffect(dismissRequested, motion.spatialEnabled) {
        val targetOpacity = if (dismissRequested) 0f else 1f
        val targetScale = if (!motion.spatialEnabled || !dismissRequested) 1f else 0.97f
        val duration = if (dismissRequested) TunesLinkMotion.ModalExit else TunesLinkMotion.ModalEnter
        coroutineScope {
            launch {
                opacity.animateTo(
                    targetOpacity,
                    androidx.compose.animation.core.tween(duration, easing = TunesLinkMotion.EaseOut),
                )
            }
            launch {
                scale.animateTo(
                    targetScale,
                    androidx.compose.animation.core.tween(duration, easing = TunesLinkMotion.EaseOut),
                )
            }
        }
        if (dismissRequested) onDismissComplete()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            dialogWindow?.setDimAmount(0f)
            onDispose { dialogWindow?.setDimAmount(0f) }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = DIALOG_DIM_AMOUNT * opacity.value))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = dialogMaxWidth)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = opacity.value
                        scaleX = scale.value
                        scaleY = scale.value
                    },
                shape = RoundedCornerShape(22.dp),
                color = TunesLinkTheme.colors.surface.copy(alpha = 0.97f),
                contentColor = TunesLinkTheme.colors.primaryText,
                tonalElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (titleIcon != null) {
                                Icon(
                                    titleIcon,
                                    contentDescription = null,
                                    tint = titleIconTint ?: TunesLinkTheme.colors.primaryText,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(
                                title,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TunesLinkTheme.colors.secondaryText,
                        )
                        content()
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val stacked = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.3f
                        if (stacked) {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                dismissLabel?.let { label ->
                                    TunesLinkTonalAction(label, onDismiss, Modifier.fillMaxWidth())
                                }
                                if (destructive) {
                                    TunesLinkTonalAction(
                                        confirmLabel,
                                        onConfirm,
                                        Modifier.fillMaxWidth(),
                                        color = TunesLinkTheme.colors.danger,
                                        enabled = confirmEnabled,
                                    )
                                } else {
                                    TunesLinkPrimaryButton(
                                        confirmLabel,
                                        onConfirm,
                                        Modifier.fillMaxWidth(),
                                        enabled = confirmEnabled,
                                        icon = confirmIcon,
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                dismissLabel?.let { label -> TunesLinkTonalAction(label, onDismiss) }
                                if (destructive) {
                                    TunesLinkTonalAction(
                                        confirmLabel,
                                        onConfirm,
                                        color = TunesLinkTheme.colors.danger,
                                        enabled = confirmEnabled,
                                    )
                                } else {
                                    TunesLinkPrimaryButton(
                                        confirmLabel,
                                        onConfirm,
                                        enabled = confirmEnabled,
                                        icon = confirmIcon,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun tunesLinkTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TunesLinkTheme.colors.primaryText,
    unfocusedTextColor = TunesLinkTheme.colors.primaryText,
    focusedContainerColor = TunesLinkTheme.colors.surface,
    unfocusedContainerColor = TunesLinkTheme.colors.surface,
    focusedBorderColor = TunesLinkTheme.colors.accentText,
    unfocusedBorderColor = TunesLinkTheme.colors.separator,
    cursorColor = TunesLinkTheme.colors.accentText,
    focusedLabelColor = TunesLinkTheme.colors.accentText,
    unfocusedLabelColor = TunesLinkTheme.colors.secondaryText,
    focusedLeadingIconColor = TunesLinkTheme.colors.secondaryText,
    unfocusedLeadingIconColor = TunesLinkTheme.colors.secondaryText,
    focusedTrailingIconColor = TunesLinkTheme.colors.secondaryText,
    unfocusedTrailingIconColor = TunesLinkTheme.colors.secondaryText,
)

@Composable
internal fun tunesLinkSliderColors(lowEmphasis: Boolean = false) = SliderDefaults.colors(
    thumbColor = if (lowEmphasis) TunesLinkTheme.colors.secondaryText else TunesLinkTheme.colors.primaryText,
    activeTrackColor = if (lowEmphasis) TunesLinkTheme.colors.secondaryText else TunesLinkTheme.colors.primaryText,
    inactiveTrackColor = TunesLinkTheme.colors.separator,
)
