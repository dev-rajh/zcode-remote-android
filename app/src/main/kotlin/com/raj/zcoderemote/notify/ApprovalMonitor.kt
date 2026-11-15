package com.raj.zcoderemote.notify

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide bus carrying notification events from the WebView JS bridge to
 * [MonitorService]. Both live in the same process, so events emitted while the
 * service is starting are buffered and delivered once collection begins.
 */
object ApprovalMonitor {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun emit(json: String) {
        _events.tryEmit(json)
    }
}
