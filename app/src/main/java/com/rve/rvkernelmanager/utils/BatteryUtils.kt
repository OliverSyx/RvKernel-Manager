/*
 * Copyright (c) 2025 Rve <rve27github@gmail.com>
 * All Rights Reserved.
 */

package com.rve.rvkernelmanager.utils

import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.roundToInt
import com.topjohnwu.superuser.Shell
import com.rve.rvkernelmanager.R
import android.os.SystemClock
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

object BatteryUtils {

    const val FAST_CHARGING = "/sys/kernel/fast_charge/force_fast_charge"
    const val BATTERY_DESIGN_CAPACITY = "/sys/class/power_supply/battery/charge_full_design"
    const val BATTERY_MAXIMUM_CAPACITY = "/sys/class/power_supply/battery/charge_full"

    const val THERMAL_SCONFIG = "/sys/class/thermal/thermal_message/sconfig"

    const val TAG = "BatteryUtils"

    private var screenLastOnTime: Long = 0
    private var screenAccumulatedTime: Long = 0
    private var screenReceiver: BroadcastReceiver? = null
    private var isScreenMonitorActive = false
    
    private fun Context.getBatteryIntent(): Intent? =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    
    fun getBatteryTechnology(context: Context): String =
        context.getBatteryIntent()?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "N/A"
    
    fun getBatteryHealth(context: Context): String {
        return when (context.getBatteryIntent()?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Unspecified Failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "N/A"
        }
    }

    fun getBatteryTemperature(context: Context): String {
        val temp = context.getBatteryIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (temp != -1) "%.1f °C".format(temp / 10.0) else "N/A"
    }

    fun getBatteryLevel(context: Context): String {
        val level = context.getBatteryIntent()?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        return if (level != -1) "$level%" else "N/A"
    }

    fun getBatteryVoltage(context: Context): String {
        val voltage = context.getBatteryIntent()?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        return if (voltage != -1) "%.3f V".format(voltage / 1000.0) else "N/A"
    }

    fun getBatteryDesignCapacity(): String = runCatching {
        val result = Shell.cmd("cat $BATTERY_DESIGN_CAPACITY").exec()
        if (result.isSuccess && result.out.isNotEmpty()) {
            val mAh = result.out.firstOrNull()?.trim()?.toIntOrNull()?.div(1000) ?: 0
            "$mAh mAh"
        } else {
            Log.w(TAG, "Failed to read design capacity: ${result.err}")
            "N/A"
        }
    }.getOrElse {
        Log.e(TAG, "Error reading design capacity", it)
        "N/A"
    }

    fun getBatteryMaximumCapacity(): String = runCatching {
        val result = Shell.cmd("cat $BATTERY_MAXIMUM_CAPACITY $BATTERY_DESIGN_CAPACITY").exec()
        if (result.isSuccess && result.out.size >= 2) {
            val maxCapacity = result.out[0].trim().toIntOrNull() ?: 0
            val designCapacity = result.out[1].trim().toIntOrNull() ?: 0
            val percentage = if (designCapacity > 0) (maxCapacity / designCapacity.toDouble() * 100).roundToInt() else 0
            "${maxCapacity / 1000} mAh ($percentage%)"
        } else "N/A"
    }.getOrElse {
        Log.e(TAG, "Error reading maximum capacity", it)
        "N/A"
    }

    private fun registerBatteryListener(
        context: Context,
        onReceive: (Intent) -> Unit
    ): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent?.let(onReceive)
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return receiver
    }

    fun registerBatteryLevelListener(context: Context, callback: (String) -> Unit): BroadcastReceiver =
        registerBatteryListener(context) { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            callback(if (level != -1) "$level%" else "N/A")
        }

    fun registerBatteryTemperatureListener(context: Context, callback: (String) -> Unit): BroadcastReceiver =
        registerBatteryListener(context) { intent ->
            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            callback(if (temp != -1) "%.1f °C".format(temp / 10.0) else "N/A")
        }

    fun registerBatteryVoltageListener(context: Context, callback: (String) -> Unit): BroadcastReceiver =
        registerBatteryListener(context) { intent ->
            val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            callback(if (voltage != -1) "%.3f V".format(voltage / 1000.0) else "N/A")
        }

    fun registerBatteryCapacityListener(context: Context, callback: (String) -> Unit): BroadcastReceiver =
        registerBatteryListener(context) {
            callback(getBatteryMaximumCapacity())
        }

        fun registerScreenMonitor(context: Context) {
        if (isScreenMonitorActive) return

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        screenLastOnTime = SystemClock.elapsedRealtime()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        if (screenLastOnTime > 0) {
                            screenAccumulatedTime += SystemClock.elapsedRealtime() - screenLastOnTime
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        context.applicationContext.registerReceiver(screenReceiver, filter)
        isScreenMonitorActive = true
    }

    fun unregisterScreenMonitor(context: Context) {
        if (isScreenMonitorActive && screenReceiver != null) {
            try {
                context.applicationContext.unregisterReceiver(screenReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Screen receiver not registered", e)
            }
            screenReceiver = null
            isScreenMonitorActive = false
        }
    }

    fun getScreenOnDuration(): Long = screenAccumulatedTime

    fun resetScreenDuration() {
        screenAccumulatedTime = 0
        screenLastOnTime = 0
    }
    
    fun getDeepSleepTimeUs(): Long = runCatching {
        val deepSleepPath = "/sys/devices/system/cpu/cpu0/cpuidle/state3/time"
        val file = File(deepSleepPath)
        if (!file.exists()) {
            Log.w(TAG, "Deep sleep file not found: $deepSleepPath")
            return@runCatching -1
        }
        
        BufferedReader(FileReader(file)).use { reader ->
            reader.readLine()?.trim()?.toLongOrNull() ?: -1
        }
    }.getOrElse {
        Log.e(TAG, "Error reading deep sleep time", it)
        -1
    }

    fun showAdvancedBatteryInfo(context: Context) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        
        val chargeCounter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val currentNow = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        Log.i(TAG, "ChargeCounter: $chargeCounter µAh")
        Log.i(TAG, "CurrentNow: $currentNow µA")
        Log.i(TAG, "Capacity: $capacity %")
    }
}
