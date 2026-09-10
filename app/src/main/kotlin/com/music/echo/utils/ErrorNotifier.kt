package com.music.echo.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ErrorNotifier {
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors = _errors.asSharedFlow()

    fun notifyError(message: String) {
        _errors.tryEmit(message)
    }
}
