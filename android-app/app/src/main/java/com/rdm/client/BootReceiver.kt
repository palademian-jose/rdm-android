package com.rdm.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON") {

            Log.d(TAG, "Boot completed, starting RDM client")

            try {
                // Start the main activity to ensure all components are initialized
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(mainIntent)

                // Also start the foreground service for persistence
                val serviceIntent = Intent(context, RdmService::class.java).apply {
                    action = RdmService.ACTION_START
                    // Put default server URL or load from preferences
                    val sharedPrefs = context.getSharedPreferences("RdmClient", Context.MODE_PRIVATE)
                    val serverUrl = sharedPrefs.getString("server_url", "wss://separately-touched-manatee.ngrok-free.app/ws/device")
                    putExtra(RdmService.EXTRA_SERVER_URL, serverUrl)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                Log.d(TAG, "RDM Client started on boot - Activity and Service")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start RDM client on boot", e)
            }
        }
    }
}