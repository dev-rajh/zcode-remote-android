package com.raj.zcoderemote

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Link-this-device screen (first run / re-linking). */
@Serializable
data object Link : NavKey

/** QR scanner used to capture the remote-control link. */
@Serializable
data object Scanner : NavKey

/** Settings: paired devices, notification preferences, unlink. */
@Serializable
data object Settings : NavKey
