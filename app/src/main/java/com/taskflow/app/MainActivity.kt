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
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

enum class RepeatType { NONE, DAILY, WEEKLY, MONTHLY }
enum class TaskStatus { ACTIVE, COMPLETED, CANCELLED, SKIPPED }
enum class Priority { LOW, MEDIUM, HIGH }
enum class Category { WORK, STUDY, PERSONAL, HOME, SHOPPING, HEALTH, OTHER }

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
    val notificationEnabled: Boolean = true,
    val priority: Priority = Priority.MEDIUM,
    val category: Category = Category.OTHER
)

fun categoryName(c: Category) = when(c){ Category.WORK->"💼 شغل"; Category.STUDY->"📚 دراسة"; Category.PERSONAL->"👤 شخصي"; Category.HOME->"🏠 بيت"; Category.SHOPPING->"🛒 تسوق"; Category.HEALTH->"❤️ صحة"; Category.OTHER->"📦 عام" }
fun categoryColor(c: Category) = when(c){ Category.WORK->Color(0xFF90CAF9); Category.STUDY->Color(0xFFFFCC80); Category.PERSONAL->Color(0xFFCE93D8); Category.HOME->Color(0xFFA5D6A7); Category.SHOPPING->Color(0xFFFFAB91); Category.HEALTH->Color(0xFFF48FB1); Category.OTHER->Color(0xFFB0BEC5) }

// ... نفس TaskStorage و NotificationHelper زي قبل بس زود category

class TaskStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("taskflow", Context.MODE_PRIVATE)
    fun load(): List<Task> {
        val json = prefs.getString("tasks", "[]") ?: "[]"
        val array = JSONArray(json)
        val result = mutableListOf<Task>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val days = mutableSetOf<Int>()
            o.optJSONArray("days")?.let { arr -> for(j in 0 until arr.length()) days.add(arr.getInt(j)) }
            result.add(Task(id=o.getLong("id"), title=o.getString("title"), description=o.optString("description"), dueTime=o.getLong("dueTime"), repeatType=RepeatType.valueOf(o.optString("repeatType","NONE")), repeatDays=days, status=TaskStatus.valueOf(o.optString("status","ACTIVE")), originalDate=o.optLong("originalDate",o.getLong("dueTime")), carriedOver=o.optBoolean("carriedOver",false), notificationEnabled=o.optBoolean("notificationEnabled",true), priority=Priority.valueOf(o.optString("priority","MEDIUM")), category=Category.valueOf(o.optString("category","OTHER"))))
        }
        return result
    }
    fun save(tasks: List<Task>) {
        val array = JSONArray()
        tasks.forEach { task ->
            val o = JSONObject()
            o.put("id", task.id); o.put("title", task.title); o.put("description", task.description); o.put("dueTime", task.dueTime); o.put("repeatType", task.repeatType.name); o.put("status", task.status.name); o.put("originalDate", task.originalDate); o.put("carriedOver", task.carriedOver); o.put("notificationEnabled", task.notificationEnabled); o.put("priority", task.priority.name); o.put("category", task.category.name)
            val days = JSONArray(); task.repeatDays.forEach { days.put(it) }; o.put("days", days); array.put(o)
        }
        prefs.edit().putString("tasks", array.toString()).apply()
    }
    fun isDarkMode(): Boolean = prefs.getBoolean("dark_mode", false)
    fun setDarkMode(enabled: Boolean) = prefs.edit().putBoolean("dark_mode", enabled).apply()
}

class NotificationHelper(private val context: Context) {
    private val channelId = "taskflow_tasks"
    init {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "TaskFlow Tasks", NotificationManager.IMPORTANCE_HIGH); manager.createNotificationChannel(channel)
    }
    fun schedule(task: Task) {
        if (!task.notificationEnabled) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java).apply { putExtra("title", task.title); putExtra("id", task.id) }
        val pendingIntent = PendingIntent.getBroadcast(context, task.id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (task.dueTime <= System.currentTimeMillis()) return
        try { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.dueTime, pendingIntent) } catch (_: SecurityException) { alarmManager.set(AlarmManager.RTC_WAKEUP, task.dueTime, pendingIntent) }
    }
    fun cancel(task: Task) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(context, task.id.toInt(), Intent(context, NotificationReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pending)
    }
    fun playSuccessSound() { try { RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play() } catch (_:Exception){} }
}

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "لديك مهمة"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, "taskflow_tasks").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("TaskFlow 🔔").setContentText(title).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}

class TaskViewModel(private val context: Context) : ViewModel() {
    private val storage = TaskStorage(context)
    private val notifications = NotificationHelper(context)
    private val _tasks = MutableStateFlow(storage.load())
    val tasks = _tasks.asStateFlow()
    private val _isDark = MutableStateFlow(storage.isDarkMode())
    val isDark = _isDark.asStateFlow()
    private fun updateList(newTasks: List<Task>) { _tasks.value = newTasks; storage.save(newTasks) }
    fun toggleDarkMode() { _isDark.value = !_isDark.value; storage.setDarkMode(_isDark.value) }
    fun addTask(title: String, description: String, date: Long, repeat: RepeatType, days: Set<Int>, notification: Boolean, priority: Priority, category: Category) {
        val task = Task(id=System.currentTimeMillis(), title=title, description=description, dueTime=date, repeatType=repeat, repeatDays=days, originalDate=date, notificationEnabled=notification, priority=priority, category=category)
        updateList(_tasks.value + task); notifications.schedule(task)
    }
    fun editTask(oldTask: Task, title: String, description: String, priority: Priority, repeat: RepeatType, days: Set<Int>, notification: Boolean, category: Category) {
        notifications.cancel(oldTask); val newTask = oldTask.copy(title=title, description=description, priority=priority, repeatType=repeat, repeatDays=days, notificationEnabled=notification, category=category)
        updateList(_tasks.value.map { if(it.id==oldTask.id) newTask else it }); notifications.schedule(newTask)
    }
    fun deleteTask(task: Task) { notifications.cancel(task); updateList(_tasks.value.filterNot { it.id == task.id }) }
    fun complete(task: Task) {
        notifications.cancel(task); notifications.playSuccessSound()
        if (task.repeatType != RepeatType.NONE) {
            val cal = Calendar.getInstance().apply{ timeInMillis=task.dueTime }
            when(task.repeatType){ RepeatType.DAILY->cal.add(Calendar.DAY_OF_YEAR,1); RepeatType.WEEKLY->cal.add(Calendar.WEEK_OF_YEAR,1); RepeatType.MONTHLY->cal.add(Calendar.MONTH,1); else->{}
            }
            val repeated = task.copy(id=System.currentTimeMillis(), dueTime=cal.timeInMillis, originalDate=cal.timeInMillis, status=TaskStatus.ACTIVE, carriedOver=false)
            updateList(_tasks.value.filterNot { it.id == task.id } + repeated); notifications.schedule(repeated); return
        }
        updateList(_tasks.value.map { if (it.id == task.id) it.copy(status=TaskStatus.COMPLETED) else it })
    }
    fun cancel(task: Task) { notifications.cancel(task); updateList(_tasks.value.map { if (it.id == task.id) it.copy(status=TaskStatus.CANCELLED) else it }) }
    fun skip(task: Task) { notifications.cancel(task); updateList(_tasks.value.map { if (it.id == task.id) it.copy(status=TaskStatus.SKIPPED) else it }) }
    fun carryToTomorrow(task: Task) {
        notifications.cancel(task)
        val tomorrow = Calendar.getInstance().apply { timeInMillis = task.dueTime; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        val newTask = task.copy(id=System.currentTimeMillis(), dueTime=tomorrow, carriedOver=true, status=TaskStatus.ACTIVE)
        updateList(_tasks.value.filterNot { it.id == task.id } + newTask); notifications.schedule(newTask)
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: TaskViewModel
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = TaskViewModel(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        setContent { val isDark by viewModel.isDark.collectAsState(); MaterialTheme(colorScheme = if(isDark) darkColorScheme() else lightColorScheme()) { TaskFlowApp(viewModel) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFlowApp(viewModel: TaskViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<Task?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<Category?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    val tasks by viewModel.tasks.collectAsState()
    val isDark by viewModel.isDark.collectAsState()
    
    val todayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }.timeInMillis
    val tomorrowStart = todayStart + 24*60*60*1000
    val baseToday = tasks.filter { it.status==TaskStatus.ACTIVE && it.dueTime>=todayStart && it.dueTime<tomorrowStart }
    val baseOverdue = tasks.filter { it.status==TaskStatus.ACTIVE && it.dueTime<todayStart }
    val baseUpcoming = tasks.filter { it.status==TaskStatus.ACTIVE && it.dueTime>=tomorrowStart }
    
    fun filterList(list: List<Task>): List<Task> {
        var filtered = list
        if(searchQuery.isNotBlank()) filtered = filtered.filter{ it.title.contains(searchQuery, true) || it.description.contains(searchQuery, true) }
        if(selectedCategory!=null) filtered = filtered.filter{ it.category==selectedCategory }
        return filtered.sortedBy{ if(it.priority==Priority.HIGH) 0 else if(it.priority==Priority.MEDIUM) 1 else 2 }
    }
    
    Scaffold(
        topBar = { 
          if(showSearch){
            TopAppBar(title={ OutlinedTextField(value=searchQuery, onValueChange={searchQuery=it}, placeholder={Text("دور على مهمة...")}, singleLine=true, modifier=Modifier.fillMaxWidth()) }, navigationIcon={ IconButton(onClick={showSearch=false; searchQuery=""}){ Icon(Icons.Default.Close,null) } })
          } else {
            TopAppBar(title={ Text(when(selectedTab){0->"اليوم";1->"المتأخرة";2->"القادمة";else->"التقرير"}, fontWeight=FontWeight.Bold) }, 
            actions={ 
              IconButton(onClick={showSearch=true}){ Icon(Icons.Default.Search, contentDescription="Search") }
              IconButton(onClick={viewModel.toggleDarkMode()}){ Icon(if(isDark) Icons.Default.LightMode else Icons.Default.DarkMode, null) }
              IconButton(onClick={showAdd=true}){ Icon(Icons.Default.Add, null) } 
            })
          }
        },
        bottomBar = { NavigationBar { NavigationBarItem(selected=selectedTab==0, onClick={selectedTab=0}, icon={Icon(Icons.Default.Today,null)}, label={Text("اليوم")}); NavigationBarItem(selected=selectedTab==1, onClick={selectedTab=1}, icon={Icon(Icons.Default.Warning,null)}, label={Text("متأخرة")}); NavigationBarItem(selected=selectedTab==2, onClick={selectedTab=2}, icon={Icon(Icons.Default.DateRange,null)}, label={Text("القادمة")}); NavigationBarItem(selected=selectedTab==3, onClick={selectedTab=3}, icon={Icon(Icons.Default.BarChart,null)}, label={Text("تقرير")}) } },
        floatingActionButton = { FloatingActionButton(onClick={showAdd=true}){ Icon(Icons.Default.Add, null) } }
    ) { padding ->
        Column(modifier=Modifier.padding(padding)) {
          if(selectedTab!=3){
            LazyRow(contentPadding=PaddingValues(horizontal=16.dp, vertical=8.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
              item { FilterChip(selected=selectedCategory==null, onClick={selectedCategory=null}, label={Text("الكل")}) }
              items(Category.entries.toList()){ cat -> FilterChip(selected=selectedCategory==cat, onClick={selectedCategory=if(selectedCategory==cat) null else cat}, label={Text(categoryName(cat))}, colors=FilterChipDefaults.filterChipColors(selectedContainerColor=categoryColor(cat))) }
            }
          }
          when(selectedTab){
            3 -> ReportScreen(tasks)
            else -> {
              val list = when(selectedTab){0->filterList(baseToday);1->filterList(baseOverdue);else->filterList(baseUpcoming)}
              if (list.isEmpty()) {
                Box(modifier=Modifier.fillMaxSize(), contentAlignment=Alignment.Center) {
                  Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, null, modifier=Modifier.size(64.dp), tint=Color(0xFF4CAF50))
                    Spacer(Modifier.height(12.dp)); Text(if(searchQuery.isNotBlank()) "مفيش نتائج لـ '$searchQuery'" else if(selectedTab==0) "مفيش مهام لليوم 🎉" else "لا توجد مهام")
                  }
                }
              } else {
                LazyColumn(contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                  items(list, key={it.id}) { task -> TaskCard(task=task, onComplete={viewModel.complete(task)}, onCarry={viewModel.carryToTomorrow(task)}, onCancel={viewModel.cancel(task)}, onSkip={viewModel.skip(task)}, onEdit={editingTask=task}, onDelete={viewModel.deleteTask(task)}) }
                }
              }
            }
          }
        }
    }
    if (showAdd) { AddTaskDialog(onDismiss={showAdd=false}, onAdd={ t,d,date,r,days,n,p,c -> viewModel.addTask(t,d,date,r,days,n,p,c); showAdd=false }) }
    if (editingTask != null) { EditTaskDialog(task=editingTask!!, onDismiss={editingTask=null}, onSave={ t,d,p,r,days,n,c -> viewModel.editTask(editingTask!!, t,d,p,r,days,n,c); editingTask=null }, onDelete={viewModel.deleteTask(editingTask!!); editingTask=null}) }
}

@Composable
fun TaskCard(task: Task, onComplete: ()->Unit, onCarry: ()->Unit, onCancel: ()->Unit, onSkip: ()->Unit, onEdit: ()->Unit, onDelete: ()->Unit) {
    var menu by remember { mutableStateOf(false) }
    val overdue = task.dueTime < Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }.timeInMillis
    val priorityColor = when(task.priority){ Priority.HIGH->Color(0xFFFFCDD2); Priority.MEDIUM->Color(0xFFFFF9C4); Priority.LOW->Color(0xFFC8E6C9) }
    val priorityDot = when(task.priority){ Priority.HIGH->Color(0xFFD32F2F); Priority.MEDIUM->Color(0xFFFBC02D); Priority.LOW->Color(0xFF388E3C) }
    Card(modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(16.dp), colors=CardDefaults.cardColors(containerColor=if(overdue) Color(0xFFFFEBEE) else priorityColor)) {
        Row(modifier=Modifier.fillMaxWidth().padding(16.dp), verticalAlignment=Alignment.CenterVertically) {
            Card(modifier=Modifier.size(10.dp), shape=RoundedCornerShape(50), colors=CardDefaults.cardColors(containerColor=priorityDot)){}
            Spacer(Modifier.width(8.dp))
            Checkbox(checked=false, onCheckedChange={onComplete()}); Spacer(Modifier.width(8.dp))
            Column(modifier=Modifier.weight(1f)) {
                Row(verticalAlignment=Alignment.CenterVertically){ Text(task.title, fontWeight=FontWeight.Bold, maxLines=1); Spacer(Modifier.width(6.dp)); Text(categoryName(task.category), style=MaterialTheme.typography.labelSmall) }
                if(task.description.isNotBlank()) Text(task.description, style=MaterialTheme.typography.bodySmall, maxLines=1)
                Spacer(Modifier.height(4.dp)); Text(formatDate(task.dueTime), style=MaterialTheme.typography.labelSmall, color=if(overdue) Color.Red else MaterialTheme.colorScheme.primary)
                if(task.carriedOver) Text("🔄 مرحّلة", color=Color(0xFFE65100), style=MaterialTheme.typography.labelSmall)
            }
            Box {
                IconButton(onClick={menu=true}){ Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(expanded=menu, onDismissRequest={menu=false}) {
                    DropdownMenuItem(text={Text("تعديل")}, onClick={menu=false; onEdit()}, leadingIcon={Icon(Icons.Default.Edit,null)})
                    DropdownMenuItem(text={Text("تمت ✅")}, onClick={menu=false; onComplete()}, leadingIcon={Icon(Icons.Default.Check,null)})
                    DropdownMenuItem(text={Text("رحّل لبكرة")}, onClick={menu=false; onCarry()}, leadingIcon={Icon(Icons.Default.Refresh,null)})
                    DropdownMenuItem(text={Text("حذف نهائي")}, onClick={menu=false; onDelete()}, leadingIcon={Icon(Icons.Default.Delete,null)})
                }
            }
        }
    }
}

@Composable
fun ReportScreen(tasks: List<Task>) {
  val todayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }.timeInMillis
  val weekStart = todayStart - 6*24*60*60*1000L
  val completedWeek = tasks.count { it.status==TaskStatus.COMPLETED && it.originalDate>=weekStart }
  val active = tasks.count { it.status==TaskStatus.ACTIVE }
  val overdue = tasks.count { it.status==TaskStatus.ACTIVE && it.dueTime<todayStart }
  val total = tasks.size
  val completionRate = if(total>0) (tasks.count{it.status==TaskStatus.COMPLETED}*100/total) else 0
  LazyColumn(modifier=Modifier.fillMaxSize().padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
    item { Card(modifier=Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=Color(0xFFE8F5E9))) { Column(modifier=Modifier.padding(16.dp)) { Text("📊 تقرير الإنجاز", fontWeight=FontWeight.Bold, style=MaterialTheme.typography.titleLarge); Spacer(Modifier.height(12.dp)); LinearProgressIndicator(progress={completionRate/100f}, modifier=Modifier.fillMaxWidth().height(10.dp)); Spacer(Modifier.height(8.dp)); Text("$completionRate% مكتمل من إجمالي $total مهمة") } } }
    item { Row(modifier=Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(10.dp)) { StatCard("✅ الأسبوع", "$completedWeek", Color(0xFF2196F3), Modifier.weight(1f)); StatCard("⏳ نشطة", "$active", Color(0xFFFF9800), Modifier.weight(1f)) } }
    item { Row(modifier=Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(10.dp)) { StatCard("⚠️ متأخرة", "$overdue", Color(0xFFF44336), Modifier.weight(1f)); StatCard("📁 الأقسام", "${Category.entries.size}", Color(0xFF9C27B0), Modifier.weight(1f)) } }
    item { Card(modifier=Modifier.fillMaxWidth()) { Column(modifier=Modifier.padding(16.dp)) { Text("حسب الأقسام", fontWeight=FontWeight.Bold); Spacer(Modifier.height(8.dp)); Category.entries.forEach { cat -> val count = tasks.count{it.category==cat && it.status==TaskStatus.ACTIVE}; if(count>0) Text("${categoryName(cat)}: $count نشطة", modifier=Modifier.padding(vertical=2.dp)) } } } }
  }
}

@Composable
fun StatCard(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
  Card(modifier=modifier, colors=CardDefaults.cardColors(containerColor=color.copy(alpha=0.15f))) { Column(modifier=Modifier.padding(16.dp), horizontalAlignment=Alignment.CenterHorizontally) { Text(title, style=MaterialTheme.typography.labelMedium); Spacer(Modifier.height(4.dp)); Text(value, fontWeight=FontWeight.Bold, style=MaterialTheme.typography.headlineMedium, color=color) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskDialog(onDismiss: ()->Unit, onAdd: (String,String,Long,RepeatType,Set<Int>,Boolean,Priority,Category)->Unit) {
    var title by remember { mutableStateOf("") }; var description by remember { mutableStateOf("") }; var repeat by remember { mutableStateOf(RepeatType.NONE) }; var notification by remember { mutableStateOf(true) }; var selectedDays by remember { mutableStateOf(setOf<Int>()) }; var dateMillis by remember { mutableStateOf(System.currentTimeMillis()+60*60*1000) }; var priority by remember { mutableStateOf(Priority.MEDIUM) }; var category by remember { mutableStateOf(Category.OTHER) }
    var showDatePicker by remember { mutableStateOf(false) }; var showTimePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis=dateMillis)
    val timeState = rememberTimePickerState(initialHour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY)+1, initialMinute=0, is24Hour=false)
    AlertDialog(onDismissRequest=onDismiss, title={Text("إضافة مهمة")}, text={
        Column(modifier=Modifier.fillMaxWidth()) {
            OutlinedTextField(value=title, onValueChange={title=it}, label={Text("اسم المهمة")}, singleLine=true, modifier=Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp))
            Row(modifier=Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick={showDatePicker=true}, modifier=Modifier.weight(1f)){ Icon(Icons.Default.DateRange,null, modifier=Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(SimpleDateFormat("dd/MM", Locale("ar")).format(Date(dateMillis)), maxLines=1) }
              OutlinedButton(onClick={showTimePicker=true}, modifier=Modifier.weight(1f)){ Icon(Icons.Default.AccessTime,null, modifier=Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(SimpleDateFormat("hh:mm a", Locale("ar")).format(Date(dateMillis)), maxLines=1) }
            }
            Spacer(Modifier.height(8.dp)); Text("القسم", fontWeight=FontWeight.Bold)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(4.dp)){ items(Category.entries.toList()){ c -> FilterChip(selected=category==c, onClick={category=c}, label={Text(categoryName(c))}) } }
            Spacer(Modifier.height(8.dp)); Text("الأهمية", fontWeight=FontWeight.Bold)
            Row{ Priority.entries.forEach { p -> FilterChip(selected=priority==p, onClick={priority=p}, label={Text(when(p){Priority.HIGH->"🔴";Priority.MEDIUM->"🟡";Priority.LOW->"🟢"})}, modifier=Modifier.padding(end=4.dp)) } }
        }
    }, confirmButton={ Button(enabled=title.isNotBlank(), onClick={
      val cal = Calendar.getInstance().apply{ timeInMillis=dateMillis; set(Calendar.HOUR_OF_DAY, timeState.hour); set(Calendar.MINUTE, timeState.minute) }
      onAdd(title, description, cal.timeInMillis, repeat, selectedDays, notification, priority, category)
    }){ Text("إضافة") } }, dismissButton={ TextButton(onClick=onDismiss){ Text("إلغاء") } })
    if(showDatePicker){ DatePickerDialog(onDismissRequest={showDatePicker=false}, confirmButton={ TextButton(onClick={ datePickerState.selectedDateMillis?.let{dateMillis=it}; showDatePicker=false }){Text("OK")} }, dismissButton={ TextButton(onClick={showDatePicker=false}){Text("إلغاء")} }){ DatePicker(state=datePickerState) } }
    if(showTimePicker){ AlertDialog(onDismissRequest={showTimePicker=false}, title={Text("اختر الوقت")}, text={ TimePicker(state=timeState) }, confirmButton={ TextButton(onClick={ val cal = Calendar.getInstance().apply{ timeInMillis=dateMillis; set(Calendar.HOUR_OF_DAY, timeState.hour); set(Calendar.MINUTE, timeState.minute) }; dateMillis=cal.timeInMillis; showTimePicker=false }){Text("OK")} }, dismissButton={ TextButton(onClick={showTimePicker=false}){Text("إلغاء")} }) }
}

@Composable
fun EditTaskDialog(task: Task, onDismiss: ()->Unit, onSave: (String,String,Priority,RepeatType,Set<Int>,Boolean,Category)->Unit, onDelete: ()->Unit) {
    var title by remember { mutableStateOf(task.title) }; var description by remember { mutableStateOf(task.description) }; var repeat by remember { mutableStateOf(task.repeatType) }; var notification by remember { mutableStateOf(task.notificationEnabled) }; var selectedDays by remember { mutableStateOf(task.repeatDays) }; var priority by remember { mutableStateOf(task.priority) }; var category by remember { mutableStateOf(task.category) }
    AlertDialog(onDismissRequest=onDismiss, title={Text("تعديل مهمة")}, text={
        Column {
            OutlinedTextField(value=title, onValueChange={title=it}, label={Text("اسم المهمة")}, singleLine=true, modifier=Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement=Arrangement.spacedBy(4.dp)){ items(Category.entries.toList()){ c -> FilterChip(selected=category==c, onClick={category=c}, label={Text(categoryName(c))}) } }
            Spacer(Modifier.height(8.dp)); Row{ Priority.entries.forEach { p -> FilterChip(selected=priority==p, onClick={priority=p}, label={Text(when(p){Priority.HIGH->"🔴";Priority.MEDIUM->"🟡";Priority.LOW->"🟢"})}, modifier=Modifier.padding(end=4.dp)) } }
        }
    }, confirmButton={ Button(enabled=title.isNotBlank(), onClick={onSave(title, description, priority, repeat, selectedDays, notification, category)}){ Text("حفظ") } }, dismissButton={ Row{ TextButton(onClick=onDelete){ Text("حذف", color=Color.Red) }; TextButton(onClick=onDismiss){ Text("إلغاء") } } })
}

fun formatDate(millis: Long): String = SimpleDateFormat("EEEE، dd/MM - hh:mm a", Locale("ar")).format(Date(millis))
fun repeatName(type: RepeatType): String = when(type){RepeatType.NONE->"مرة واحدة";RepeatType.DAILY->"يوميًا";RepeatType.WEEKLY->"أسبوعيًا";RepeatType.MONTHLY->"شهريًا"}
