/*
 * Copyright (c) 2025 Rve <rve27github@gmail.com>
 * All Rights Reserved.
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.rve.rvkernelmanager.ui.activity

import android.os.Bundle

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.material3.*
import androidx.navigation.compose.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

import com.rve.rvkernelmanager.utils.BatteryUtils
import com.rve.rvkernelmanager.ui.navigation.*
import com.rve.rvkernelmanager.ui.screen.*
import com.rve.rvkernelmanager.ui.theme.RvKernelManagerTheme

import com.topjohnwu.superuser.Shell

class MainActivity : ComponentActivity() {
    private var isRoot = false
    private var showRootDialog by mutableStateOf(false)
    private var batteryReceiver: BroadcastReceiver? = null
    
    private val checkRoot = Runnable {
        Shell.getShell { shell ->
            isRoot = shell.isRoot
            if (!isRoot) {
                showRootDialog = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen: SplashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !isRoot }
        enableEdgeToEdge()
        Thread(checkRoot).start()

        setContent {
            RvKernelManagerTheme {
                RvKernelManagerApp(
                    showRootDialog = showRootDialog,
                    onBatteryInfoRequest = ::showBatteryInfo,
                    onResetTrackers = ::resetTrackers
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        BatteryUtils.registerScreenMonitor(applicationContext)
        
        batteryReceiver = BatteryUtils.registerBatteryLevelListener(this) { level ->
            Log.d("BatteryLevel", "Current level: $level")
        }
    }

    override fun onStop() {
        super.onStop()
        BatteryUtils.unregisterScreenMonitor(applicationContext)
        
        batteryReceiver?.let {
            unregisterReceiver(it)
            batteryReceiver = null
        }
    }

    private fun showBatteryInfo() {
        val health = BatteryUtils.getBatteryHealth(this)
        val temp = BatteryUtils.getBatteryTemperature(this)
        val maxCapacity = BatteryUtils.getBatteryMaximumCapacity()
        val screenTime = BatteryUtils.getScreenOnDuration()
        val deepSleep = BatteryUtils.getDeepSleepTimeUs()
        
        Log.d("BatteryInfo", "Health: $health, Temp: $temp")
        Log.d("BatteryInfo", "Max Capacity: $maxCapacity")
        Log.d("UsageInfo", "Screen On: ${screenTime/1000}s, Deep Sleep: ${deepSleep/1000}ms")
        
        BatteryUtils.showAdvancedBatteryInfo(this)
    }
    
    private fun resetTrackers() {
        BatteryUtils.resetScreenDuration()
    }

    companion object {
        init {
            @Suppress("DEPRECATION")
            if (Shell.getCachedShell() == null) {
                Shell.setDefaultBuilder(
                    Shell.Builder.create()
                        .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                        .setTimeout(20)
                )
            }
        }
    }
}

@Composable
fun RvKernelManagerApp(
    showRootDialog: Boolean = false,
    onBatteryInfoRequest: () -> Unit = {},
    onResetTrackers: () -> Unit = {}
) {
    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current

    if (showRootDialog) {
        AlertDialog(
            onDismissRequest = {},
            text = {
                Text(
                    text = "RvKernel Manager requires root access!",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        System.exit(0)
                    },
                ) {
                    Text("Exit")
                }
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onBatteryInfoRequest,
                icon = { Icon(Icons.Default.BatteryChargingFull, "Battery Info") },
                text = { Text("Show Battery Info") }
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    lifecycleOwner = lifecycleOwner,
                    navController = navController,
                    onResetTrackers = onResetTrackers
                )
            }
            composable("soc") {
                SoCScreen(lifecycleOwner = lifecycleOwner, navController = navController)
            }
            composable("battery") {
                BatteryScreen(lifecycleOwner = lifecycleOwner, navController = navController)
            }
            composable("kernel") {
                KernelParameterScreen(lifecycleOwner = lifecycleOwner, navController = navController)
            }
        }
    }
}
