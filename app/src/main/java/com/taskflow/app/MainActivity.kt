package com.taskflow.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class RepeatType {
    NONE,
    DAILY,
    WEEKLY,
    MONTHLY
}

enum class TaskStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
    SKIPPED
}

data class Task(
    val id: Long,
    val title: String,
    val description: String = "",
    val dueTime: Long,
    val repeatType: RepeatType = RepeatType.NONE,
    val repeatDays: Set<Int> = emptySet(),
    val status: TaskStatus = TaskStatus.ACTIVE,
    val originalDate: Long = dueTime,
    val carriedOver: Boolean = false,
    val notificationEnabled: Boolean = true
)

class TaskStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("taskflow", Context.MODE_PRIVATE)

    fun load(): List<Task> {
        val json = prefs.getString("tasks", "[]") ?: "[]"
        val array = JSONArray(json)
        val result = mutableListOf<Task>()

        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val days = mutableSetOf<Int>()
            val daysArray = o.optJSONArray("days")

            if (daysArray != null) {
                for (j in 0 until daysArray.length()) {
                    days.add(daysArray.getInt(j))
                }
            }

            result.add(
                Task(
                    id = o.getLong("id"),
                    title = o.getString("title"),
                    description = o.optString("description"),
                    dueTime = o.getLong("dueTime"),
                    repeatType = RepeatType.valueOf(o.optString("repeatType", "NONE")),
                    repeatDays = days,
                    status = TaskStatus.valueOf(o.optString("status", "ACTIVE")),
                    originalDate = o.optLong("originalDate", o.getLong("dueTime")),
                    carriedOver = o.optBoolean("carriedOver", false),
                    notificationEnabled = o.optBoolean("notificationEnabled", true)
                )
            )
        }

        return result
    }

    fun save(tasks: List<Task>) {
        val array = JSONArray()

        tasks.forEach { task ->
            val o = JSONObject()
            o.put("id", task.id)
            o.put("title", task.title)
            o.put("description", task.description)
            o.put("dueTime", task.dueTime)
            o.put("repeatType", task.repeatType.name)
            o.put("status", task.status.name)
            o.put("originalDate", task.originalDate)
            o.put("carriedOver", task.carriedOver)
            o.put("notificationEnabled", task.notificationEnabled)

            val days = JSONArray()
            task.repeatDays.forEach { days.put(it) }
            o.put("days", days)
            array.put(o)
        }

        prefs.edit().putString("tasks", array.toString()).apply()
    }
}

class NotificationHelper(private val context: Context) {
    private val channelId = "taskflow_tasks"

    init {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "TaskFlow Tasks", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Task reminders"
        manager.createNotificationChannel(channel)
    }

    fun schedule(task: Task) {
        if (!task.notificationEnabled) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("title", task.title)
            putExtra("id", task.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (task.dueTime <= System.currentTimeMillis()) return

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                task.dueTime,
                pendingIntent
            )
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, task.dueTime, pendingIntent)
        }
    }

    fun cancel(task: Task) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            task.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pending)
    }
}

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "لديك مهمة"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "taskflow_tasks"

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TaskFlow")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}

class TaskViewModel(private val context: Context) : ViewModel() {
    private val storage = TaskStorage(context)
    private val notifications = NotificationHelper(context)
    private val _tasks = MutableStateFlow(storage.load())

    val tasks = _tasks.asStateFlow()

    fun addTask(
        title: String,
        description: String,
        date: Long,
        repeat: RepeatType,
        days: Set<Int>,
        notification: Boolean
    ) {
        val task = Task(
            id = System.currentTimeMillis(),
            title = title,
            description = description,
            dueTime = date,
            repeatType = repeat,
            repeatDays = days,
            originalDate = date,
            notificationEnabled = notification
        )

        update(_tasks.value + task)
        notifications.schedule(task)
    }

    fun complete(task: Task) {
        notifications.cancel(task)

        if (task.repeatType != RepeatType.NONE) {
            val next = calculateNextOccurrence(task)
            if (next != null) {
                val repeated = task.copy(
                    id = System.currentTimeMillis(),
                    dueTime = next,
                    originalDate = next,
                    status = TaskStatus.ACTIVE,
                    carriedOver = false
                )

                update(_tasks.value.filterNot { it.id == task.id } + repeated)
                notifications.schedule(repeated)
                return
            }
        }

        update(
            _tasks.value.map {
                if (it.id == task.id) it.copy(status = TaskStatus.COMPLETED) else it
            }
        )
    }

    fun cancel(task: Task) {
        notifications.cancel(task)
        update(
            _tasks.value.map {
                if (it.id == task.id) it.copy(status = TaskStatus.CANCELLED) else it
            }
        )
    }

    fun skip(task: Task) {
        notifications.cancel(task)
        update(
            _tasks.value.map {
                if (it.id == task.id) it.copy(status = TaskStatus.SKIPPED) else it
            }
        )
    }

    fun carryToTomorrow(task: Task) {
        notifications.cancel(task)
        val tomorrow = Calendar.getInstance().apply {
            time
