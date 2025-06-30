package com.steptracker.bridge

import com.facebook.react.bridge.*
import com.steptracker.data.StepsDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StepTrackerModule(rc: ReactApplicationContext) : ReactContextBaseJavaModule(rc) {
    override fun getName() = "StepTrackerModule"

    private val dao = StepsDatabase.get(rc).dao()
    private val io  = CoroutineScope(Dispatchers.IO)

    @ReactMethod fun getTodaySteps(promise: Promise) {
        io.launch {
            val today = java.time.LocalDate.now().toString()
            val steps = dao.summaryFor(today)?.steps ?: 0
            promise.resolve(steps)
        }
    }
}