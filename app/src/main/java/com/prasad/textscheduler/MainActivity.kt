@file:OptIn(ExperimentalMaterial3Api::class)

package com.prasad.textscheduler

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsMessage
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Surface(Modifier.fillMaxSize()) { App(this@MainActivity) }
            }
        }
    }
}

@Composable
fun App(activity: ComponentActivity) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(Store.all(ctx)) }
    var smsGranted by remember { mutableStateOf(Scheduler.hasSmsPermission(ctx)) }
    var editing by remember { mutableStateOf<ScheduledMessage?>(null) }
    var showRestrictedHelp by remember { mutableStateOf(false) }

    fun refresh() {
        items = Store.all(ctx)
        smsGranted = Scheduler.hasSmsPermission(ctx)
    }

    // Refresh whenever the app comes back to the foreground (sends may have happened).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        refresh()
        val smsOk = result[Manifest.permission.SEND_SMS] == true || Scheduler.hasSmsPermission(ctx)
        if (!smsOk && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.SEND_SMS)) {
            showRestrictedHelp = true
        }
    }
    fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    LaunchedEffect(Unit) {
        // Re-arm alarms on launch in case anything was lost (e.g. after a force-stop).
        Scheduler.rescheduleAll(ctx)
        refresh()
        if (!smsGranted) requestPerms()
    }

    val current = editing
    if (current != null) {
        EditorScreen(
            initial = current,
            isNew = items.none { it.id == current.id },
            onClose = { editing = null; refresh() },
        )
    } else {
        ListScreen(
            items = items,
            smsGranted = smsGranted,
            onRequestSms = { requestPerms() },
            onNew = { editing = newMessage() },
            onEdit = { editing = it },
            onChanged = { refresh() },
        )
    }

    if (showRestrictedHelp) {
        AlertDialog(
            onDismissRequest = { showRestrictedHelp = false },
            title = { Text("Allow SMS permission") },
            text = {
                Text(
                    "Android blocks SMS permission for apps installed outside the Play Store until you allow it:\n\n" +
                        "1. Tap Open App info below\n" +
                        "2. Tap the ⋮ menu (top right) → Allow restricted settings, and confirm\n" +
                        "3. Tap Permissions → SMS → Allow\n\n" +
                        "Then come back here."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestrictedHelp = false
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    )
                }) { Text("Open App info") }
            },
            dismissButton = { TextButton(onClick = { showRestrictedHelp = false }) { Text("Later") } },
        )
    }
}

fun newMessage(): ScheduledMessage {
    val t = LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
    return ScheduledMessage(
        id = Store.newId(), recipients = emptyList(), body = "",
        start = t, repeat = Repeat.WEEKLY, weekDays = setOf(t.dayOfWeek),
    )
}

// ---------------- List ----------------

@Composable
fun ListScreen(
    items: List<ScheduledMessage>,
    smsGranted: Boolean,
    onRequestSms: () -> Unit,
    onNew: () -> Unit,
    onEdit: (ScheduledMessage) -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    var confirmSend by remember { mutableStateOf<ScheduledMessage?>(null) }
    var confirmDelete by remember { mutableStateOf<ScheduledMessage?>(null) }
    val sorted = items.sortedWith(compareBy<ScheduledMessage> { !it.enabled }.thenBy { it.nextAt ?: Long.MAX_VALUE })

    Scaffold(
        topBar = { TopAppBar(title = { Text("Text Scheduler") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New text") },
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!smsGranted) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("SMS permission needed", fontWeight = FontWeight.SemiBold)
                            Text("Scheduled texts can't be sent until you allow it.", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onRequestSms) { Text("Allow") }
                        }
                    }
                }
            }
            if (!Scheduler.canExact(ctx)) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Text(
                            "Exact timing is off for this app, so texts may go out a few minutes late.",
                            Modifier.padding(16.dp),
                        )
                    }
                }
            }
            if (sorted.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 96.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.DateRange, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("No scheduled texts yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tap New text to send a message on a repeating schedule.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(sorted, key = { it.id }) { m ->
                MessageCard(
                    m = m,
                    onClick = { onEdit(m) },
                    onToggle = { on ->
                        Scheduler.plan(ctx, m.copy(enabled = on)); onChanged()
                    },
                    onSendNow = { confirmSend = m },
                    onDelete = { confirmDelete = m },
                )
            }
        }
    }

    confirmSend?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmSend = null },
            title = { Text("Send now?") },
            text = { Text("This sends the text to ${m.recipients.joinToString { it.display }} right away. The schedule isn't changed.") },
            confirmButton = {
                TextButton(onClick = {
                    if (Scheduler.sendNow(ctx, m)) {
                        Store.save(ctx, m.copy(lastSentAt = System.currentTimeMillis(), sentCount = m.sentCount + 1))
                    }
                    confirmSend = null; onChanged()
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirmSend = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this scheduled text?") },
            confirmButton = {
                TextButton(onClick = {
                    Scheduler.cancel(ctx, m.id); Store.delete(ctx, m.id)
                    confirmDelete = null; onChanged()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun MessageCard(
    m: ScheduledMessage,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSendNow: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val dim = if (m.enabled) 1f else 0.55f
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 14.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Person, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = dim))
                Spacer(Modifier.width(8.dp))
                Text(
                    m.recipients.joinToString { it.display }.ifBlank { "(no recipient)" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                )
                Switch(checked = m.enabled, onCheckedChange = onToggle)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Send now") }, onClick = { menu = false; onSendNow() },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, null) })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) })
                    }
                }
            }
            Text(
                m.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                modifier = Modifier.padding(end = 12.dp, top = 2.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(Recurrence.describe(m), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim))
            val status = when {
                !m.enabled && m.nextAt == null && m.sentCount > 0 && m.repeat == Repeat.NONE -> "Sent"
                !m.enabled -> "Paused"
                m.nextAt != null -> "Next: " + Recurrence.formatWhen(m.nextAt)
                else -> "Finished"
            }
            Row {
                Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary.copy(alpha = dim))
                if (m.lastSentAt != null) {
                    Text("  ·  Last sent " + Recurrence.formatWhen(m.lastSentAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim))
                }
            }
        }
    }
}

// ---------------- Editor ----------------

private val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())
private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

@Composable
fun EditorScreen(initial: ScheduledMessage, isNew: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var recipients by remember { mutableStateOf(initial.recipients) }
    var phoneInput by remember { mutableStateOf("") }
    var body by remember { mutableStateOf(initial.body) }
    var date by remember { mutableStateOf(initial.start.toLocalDate()) }
    var time by remember { mutableStateOf(initial.time) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var interval by remember { mutableIntStateOf(initial.interval) }
    var weekDays by remember { mutableStateOf(initial.weekDays.ifEmpty { setOf(initial.start.dayOfWeek) }) }
    var endDate by remember { mutableStateOf(initial.endDate) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    val contactLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val num = c.getString(0) ?: ""
                val name = c.getString(1) ?: ""
                if (num.isNotBlank() && recipients.none { it.phone == num }) {
                    recipients = recipients + Recipient(name, num)
                }
            }
        }
    }

    fun draft(): ScheduledMessage {
        val extra = phoneInput.trim().takeIf { it.isNotEmpty() }?.let { listOf(Recipient("", it)) } ?: emptyList()
        return initial.copy(
            recipients = recipients + extra,
            body = body.trim(),
            start = LocalDateTime.of(date, time),
            repeat = repeat,
            interval = if (repeat == Repeat.NONE) 1 else interval,
            weekDays = if (repeat == Repeat.WEEKLY) weekDays else emptySet(),
            endDate = if (repeat == Repeat.NONE) null else endDate,
            enabled = true,
        )
    }

    fun save() {
        val d = draft()
        val now = System.currentTimeMillis()
        error = when {
            d.recipients.isEmpty() -> "Add at least one recipient."
            d.body.isEmpty() -> "Write a message."
            d.repeat == Repeat.WEEKLY && d.weekDays.isEmpty() -> "Pick at least one day of the week."
            Recurrence.next(d, now) == null ->
                if (d.repeat == Repeat.NONE) "That time has already passed." else "There are no send times before the end date."
            else -> null
        }
        if (error != null) return
        Scheduler.plan(ctx, d)
        onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New scheduled text" else "Edit scheduled text") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = {
                            Scheduler.cancel(ctx, initial.id); Store.delete(ctx, initial.id); onClose()
                        }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                    TextButton(onClick = { save() }) { Text("Save") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Recipients
            SectionLabel("To")
            if (recipients.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recipients.forEach { r ->
                        InputChip(
                            selected = false,
                            onClick = { recipients = recipients - r },
                            label = { Text(if (r.name.isBlank()) r.phone else "${r.name}  ${r.phone}") },
                            leadingIcon = { Icon(Icons.Filled.Person, null, Modifier.size(18.dp)) },
                            trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = { phoneInput = it },
                    label = { Text("Phone number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val p = phoneInput.trim()
                        if (p.isNotEmpty()) { recipients = recipients + Recipient("", p); phoneInput = "" }
                    },
                    enabled = phoneInput.isNotBlank(),
                ) { Icon(Icons.Filled.Add, "Add number") }
            }
            OutlinedButton(onClick = {
                contactLauncher.launch(
                    Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)
                )
            }) {
                Icon(Icons.Filled.Person, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Choose from contacts")
            }
            if (recipients.size + (if (phoneInput.isNotBlank()) 1 else 0) > 1) {
                Text("Each person gets their own text (not a group chat).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Message
            SectionLabel("Message")
            val counts = remember(body) { if (body.isEmpty()) intArrayOf(0, 0, 0, 0) else SmsMessage.calculateLength(body, false) }
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text("Type your message") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("${body.length} characters" + if (counts[0] > 1) " · sends as ${counts[0]} parts" else "")
                },
            )

            // Repeat
            SectionLabel("Repeat")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Repeat.entries.forEach { r ->
                    FilterChip(selected = repeat == r, onClick = { repeat = r }, label = { Text(r.label) })
                }
            }
            if (repeat != Repeat.NONE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Every", style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { if (interval > 1) interval-- }, enabled = interval > 1) {
                        Text("–", style = MaterialTheme.typography.titleLarge)
                    }
                    Text("$interval", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { if (interval < 99) interval++ }) { Icon(Icons.Filled.Add, "More") }
                    Text(repeat.unit + if (interval == 1) "" else "s", style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (repeat == Repeat.WEEKLY) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DayOfWeek.values().forEach { d ->
                        val on = d in weekDays
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { weekDays = if (on) weekDays - d else weekDays + d },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                d.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // When
            SectionLabel("When")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1.4f)) {
                    Text((if (repeat == Repeat.NONE) "" else "Starts ") + date.format(dateFmt), maxLines = 1)
                }
                OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.weight(1f)) {
                    Text(time.format(timeFmt))
                }
            }
            if (repeat != Repeat.NONE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickEnd = true }) {
                        Text(endDate?.let { "Ends " + it.format(dateFmt) } ?: "No end date")
                    }
                    if (endDate != null) {
                        IconButton(onClick = { endDate = null }) { Icon(Icons.Filled.Close, "Remove end date") }
                    }
                }
            }

            // Preview of upcoming sends — the easiest way to check the schedule is right.
            val preview = remember(recipients, phoneInput, body, date, time, repeat, interval, weekDays, endDate) {
                val d = draft()
                val out = mutableListOf<Long>()
                var after = System.currentTimeMillis()
                for (i in 0 until 4) {
                    val n = Recurrence.next(d, after) ?: break
                    out += n; after = n
                }
                out
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(Recurrence.describe(draft()), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    if (preview.isEmpty()) {
                        Text("No upcoming sends — pick a time in the future.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("Upcoming:", style = MaterialTheme.typography.labelLarge)
                        preview.forEach { Text("• " + Recurrence.formatWhen(it), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Check, null); Spacer(Modifier.width(8.dp)); Text("Save schedule")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickDate) {
        DateDialog(date, onDismiss = { pickDate = false }) { date = it; pickDate = false }
    }
    if (pickEnd) {
        DateDialog(endDate ?: date.plusMonths(1), onDismiss = { pickEnd = false }) { endDate = it; pickEnd = false }
    }
    if (pickTime) {
        val st = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = { time = LocalTime.of(st.hour, st.minute); pickTime = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = st) },
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun DateDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val st = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                st.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = st)
    }
}
