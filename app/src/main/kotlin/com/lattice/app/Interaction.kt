package com.lattice.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When the user last touched the app. The mirror pauses after a stretch
 * without touches and resumes on the next one — a phone left face-up on the
 * desk with the Desktop tab open must not stream frames all afternoon.
 */
object Interaction {
    private val _lastTouch = MutableStateFlow(System.currentTimeMillis())
    val lastTouch: StateFlow<Long> = _lastTouch.asStateFlow()
    fun touch() { _lastTouch.value = System.currentTimeMillis() }
}
