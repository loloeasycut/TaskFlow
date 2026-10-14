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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Replay
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
            timeInMillis = task.dueTime
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

        val newTask = task.copy(
            id = System.currentTimeMillis(),
            dueTime = tomorrow,
            carriedOver = true,
            status = TaskStatus.ACTIVE
        )

        update(_tasks.value.filterNot { it.id == task.id } + newTask)
        notifications.schedule(newTask)
    }

    fun moveToDate(task: Task, date: Long) {
        notifications.cancel(task)
        val newTask = task.copy(
            id = System.currentTimeMillis(),
            dueTime = date,
            carriedOver = true,
            status = TaskStatus.ACTIVE
        )

        update(_tasks.value.filterNot { it.id == task.id } + newTask)
        notifications.schedule(newTask)
    }

    private fun update(newTasks: List<Task>) {
        _tasks.value = newTasks
        storage.save(newTasks)
    }

    private fun calculateNextOccurrence(task: Task): Long? {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = task.dueTime

        when (task.repeatType) {
            RepeatType.DAILY -> {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }

            RepeatType.WEEKLY -> {
                if (task.repeatDays.isEmpty()) {
                    calendar.add(Calendar.WEEK_OF_YEAR, 1)
                } else {
                    for (i in 0 until 7) {
                        calendar.add(Calendar.DAY_OF_YEAR, 1)
                        val day = calendar.get(Calendar.DAY_OF_WEEK)
                        if (task.repeatDays.contains(day)) return calendar.timeInMillis
                    }
                }
            }

            RepeatType.MONTHLY -> {
                calendar.add(Calendar.MONTH, 1)
            }

            RepeatType.NONE -> return null
        }

        return calendar.timeInMillis
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: TaskViewModel

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = TaskViewModel(applicationContext)

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                TaskFlowApp(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFlowApp(viewModel: TaskViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    val tasks by viewModel.tasks.collectAsState()

    val todayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val tomorrowStart = todayStart + 24 * 60 * 60 * 1000

    val today = tasks.filter {
        it.status == TaskStatus.ACTIVE && it.dueTime >= todayStart && it.dueTime < tomorrowStart
    }.sortedBy { it.dueTime }

    val overdue = tasks.filter {
        it.status == TaskStatus.ACTIVE && it.dueTime < todayStart
    }.sortedBy { it.dueTime }

    val upcoming = tasks.filter {
        it.status == TaskStatus.ACTIVE && it.dueTime >= tomorrowStart
    }.sortedBy { it.dueTime }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (selectedTab) {
                            0 -> "اليوم"
                            1 -> "المتأخرة"
                            else -> "القادمة"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = "إضافة")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Today, null) },
                    label = { Text("اليوم") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Warning, null) },
                    label = { Text("متأخرة") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.CalendarMonth, null) },
                    label = { Text("القادمة") }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "إضافة مهمة")
            }
        }
    ) { padding ->
        val list = when (selectedTab) {
            0 -> today
            1 -> overdue
            else -> upcoming
        }

        if (list.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color(0xFF4CAF50)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(if (selectedTab == 0) "مفيش مهام لليوم 🎉" else "لا توجد مهام")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onComplete = { viewModel.complete(task) },
                        onCarry = { viewModel.carryToTomorrow(task) },
                        onCancel = { viewModel.cancel(task) },
                        onSkip = { viewModel.skip(task) }
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddTaskDialog(
            onDismiss = { showAdd = false },
            onAdd = { title, description, date, repeat, days, notification ->
                viewModel.addTask(title, description, date, repeat, days, notification)
                showAdd = false
            }
        )
    }
}

@Composable
fun TaskCard(
    task: Task,
    onComplete: () -> Unit,
    onCarry: () -> Unit,
    onCancel: () -> Unit,
    onSkip: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }

    val overdue = task.dueTime < Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (overdue) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = false, onCheckedChange = { onComplete() })
            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.Bold)

                if (task.description.isNotBlank()) {
                    Text(task.description, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    formatDate(task.dueTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overdue) Color.Red else MaterialTheme.colorScheme.primary
                )

                if (task.carriedOver) {
                    Text("🔄 مرحّلة", color = Color(0xFFE65100), style = MaterialTheme.typography.labelSmall)
                }

                if (task.repeatType != RepeatType.NONE) {
                    Text("🔁 ${repeatName(task.repeatType)}", style = MaterialTheme.typography.labelSmall)
                }
            }

            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }

                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("تمت") },
                        onClick = { menu = false; onComplete() },
                        leadingIcon = { Icon(Icons.Default.Check, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("رحّل لبكرة") },
                        onClick = { menu = false; onCarry() },
                        leadingIcon = { Icon(Icons.Default.Replay, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("تخطي") },
                        onClick = { menu = false; onSkip() },
                        leadingIcon = { Icon(Icons.Default.SkipNext, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("إلغاء") },
                        onClick = { menu = false; onCancel() },
                        leadingIcon = { Icon(Icons.Default.Delete, null) }
                    )
                }
            }
        }
    }
}

@Composable
fun AddTaskDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, Long, RepeatType, Set<Int>, Boolean) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf(RepeatType.NONE) }
    var notification by remember { mutableStateOf(true) }
    var selectedDays by remember { mutableStateOf(setOf<Int>()) }
    var date by remember { mutableStateOf(System.currentTimeMillis() + 60 * 60 * 1000) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة مهمة") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("اسم المهمة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("ملاحظات") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                Text("تكرار المهمة", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))

                RepeatType.entries.forEach { type ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { repeat = type }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = repeat == type, onClick = { repeat = type })
                        Text(repeatName(type))
                    }
                }

                if (repeat == RepeatType.WEEKLY) {
                    Text("أيام الأسبوع")
                    val days = listOf(
                        Calendar.SATURDAY to "س",
                        Calendar.SUNDAY to "ح",
                        Calendar.MONDAY to "ن",
                        Calendar.TUESDAY to "ث",
                        Calendar.WEDNESDAY to "ر",
                        Calendar.THURSDAY to "خ",
                        Calendar.FRIDAY to "ج"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        days.forEach { (day, name) ->
                            FilterChip(
                                selected = selectedDays.contains(day),
                                onClick = {
                                    selectedDays = if (selectedDays.contains(day)) selectedDays - day else selectedDays + day
                                },
                                label = { Text(name) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = notification, onCheckedChange = { notification = it })
                    Text("تنبيه للمهمة")
                }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank(),
                onClick = {
                    onAdd(title, description, date, repeat, selectedDays, notification)
                }
            ) {
                Text("إضافة")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

fun formatDate(millis: Long): String {
    return SimpleDateFormat("EEEE، dd/MM - hh:mm a", Locale("ar")).format(Date(millis))
}

fun repeatName(type: RepeatType): String {
    return when (type) {
        RepeatType.NONE -> "مرة واحدة"
        RepeatType.DAILY -> "يوميًا"
        RepeatType.WEEKLY -> "أسبوعيًا"
        RepeatType.MONTHLY -> "شهريًا"
    }
}
