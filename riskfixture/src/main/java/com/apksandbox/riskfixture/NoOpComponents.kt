package com.apksandbox.riskfixture

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder

/** Exported/non-exported no-op components (checkpoint 3, item 24) — exist only so `STATIC_MANY_EXPORTED_COMPONENTS` has real component data to count against; none is ever started intentionally. */
class NoOpActivityOne : Activity()
class NoOpActivityTwo : Activity()
class NoOpReceiverOne : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {} }
class NoOpReceiverTwo : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {} }
class NoOpServiceNotExported : Service() { override fun onBind(intent: Intent): IBinder? = null }
