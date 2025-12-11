package com.example.autoscreenshot

import kotlinx.coroutines.*

object CoroutineManager {

    private val parentJob = SupervisorJob()

    val ioScope: CoroutineScope = CoroutineScope(parentJob + Dispatchers.IO)

    val mainScope: CoroutineScope = CoroutineScope(parentJob + Dispatchers.Main)

    val defaultScope: CoroutineScope = CoroutineScope(parentJob + Dispatchers.Default)

    fun launchIO(block: suspend CoroutineScope.() -> Unit): Job {
        return ioScope.launch(block = block)
    }

    fun launchMain(block: suspend CoroutineScope.() -> Unit): Job {
        return mainScope.launch(block = block)
    }

    fun launchDefault(block: suspend CoroutineScope.() -> Unit): Job {
        return defaultScope.launch(block = block)
    }

    fun cancelAll() {
        parentJob.cancelChildren()
    }
}