package com.duplikatumkereso.scan

import com.duplikatumkereso.data.ScanOptions
import com.duplikatumkereso.data.ScanProgress
import com.duplikatumkereso.data.ScanResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/** A háttérszolgáltatás és a felület közös állapota (csak memóriában). */
object ScanState {
    val progress = MutableStateFlow<ScanProgress>(ScanProgress.Idle)
    val result = MutableStateFlow<ScanResult?>(null)

    @Volatile
    var pendingOptions: ScanOptions? = null

    @Volatile
    var job: Job? = null

    fun cancel() {
        job?.cancel()
    }
}
