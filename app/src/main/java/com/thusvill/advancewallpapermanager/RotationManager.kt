/*
 * Copyright (C) 2026 Advance Wallpaper Manager
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.thusvill.advancewallpapermanager

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

enum class RotationMode {
    NONE, DAILY, HOURLY, ON_AWAKE
}

class RotationManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("rotation_prefs", Context.MODE_PRIVATE)

    fun setRotationMode(mode: RotationMode, hourInterval: Int = 1) {
        prefs.edit().putString("rotation_mode", mode.name).putInt("hour_interval", hourInterval).apply()
        
        WorkManager.getInstance(context).cancelUniqueWork("wallpaper_rotation")
        
        when (mode) {
            RotationMode.DAILY -> scheduleWork(24)
            RotationMode.HOURLY -> scheduleWork(hourInterval)
            else -> {}
        }
    }

    fun getRotationMode(): RotationMode {
        return try {
            RotationMode.valueOf(prefs.getString("rotation_mode", RotationMode.NONE.name)!!)
        } catch (e: Exception) {
            RotationMode.NONE
        }
    }

    fun getHourInterval(): Int = prefs.getInt("hour_interval", 1)

    private fun scheduleWork(hours: Int) {
        val request = PeriodicWorkRequestBuilder<RotationWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "wallpaper_rotation",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun rotateNow() {
        val configManager = ConfigManager(context)
        val configs = configManager.loadAllConfigs()
        if (configs.isNotEmpty()) {
            val currentId = configManager.getActiveConfigId()
            val nextConfig = if (currentId == null) {
                configs.first()
            } else {
                val currentIndex = configs.indexOfFirst { it.id == currentId }
                if (currentIndex == -1 || currentIndex == configs.size - 1) configs.first()
                else configs[currentIndex + 1]
            }
            configManager.setActiveConfigId(nextConfig.id)
        }
    }
}

class RotationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): ListenableWorker.Result {
        RotationManager(applicationContext).rotateNow()
        return ListenableWorker.Result.success()
    }
}
