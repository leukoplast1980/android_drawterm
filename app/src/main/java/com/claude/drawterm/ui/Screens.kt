@file:OptIn(ExperimentalMaterial3Api::class)

package com.claude.drawterm.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.claude.drawterm.BuildConfig
import com.claude.drawterm.R
import com.claude.drawterm.data.Profile
import java.util.UUID

interface AppActions {
    fun connect(p: Profile)
    fun connectWithoutAan(p: Profile)
    fun dismissProblem(p: Profile)
    fun save(p: Profile, password: String?)
    fun delete(p: Profile)
    fun hasSavedPassword(p: Profile): Boolean
    fun requestPhoneAccess()
    fun openFiles()
}

/** The server list, or the editor for one server. */
@Composable
fun DrawtermApp(profiles: List<Profile>, problems: Map<String, String>, phoneAccess: Boolean, actions: AppActions) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val id = editing
    if (id == null) {
        ServerList(profiles, problems, actions, onEdit = { editing = it.id }, onAdd = { editing = UUID.randomUUID().toString() })
        return
    }
    val existing = profiles.find { it.id == id }
    key(id) {
        ServerEditor(
            initial = existing ?: Profile(id = id),
            isNew = existing == null,
            hasSavedPassword = existing != null && actions.hasSavedPassword(existing),
            phoneAccess = phoneAccess,
            actions = actions,
            onDuplicate = { p ->
                val copy = p.copy(id = UUID.randomUUID().toString(), name = "", lastUsed = 0L)
                actions.save(copy, null)
                editing = copy.id
            },
            onClose = { editing = null },
        )
    }
}

@Composable
private fun ServerList(
    profiles: List<Profile>,
    problems: Map<String, String>,
    actions: AppActions,
    onEdit: (Profile) -> Unit,
    onAdd: () -> Unit,
) {
    var about by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = actions::openFiles) {
                        Icon(painterResource(R.drawable.ic_folder), stringResource(R.string.files))
                    }
                    IconButton(onClick = { about = true }) {
                        Icon(painterResource(R.drawable.ic_info), stringResource(R.string.about))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(painterResource(R.drawable.ic_add), null) },
                text = { Text(stringResource(R.string.add_server)) },
            )
        },
    ) { inner ->
        if (profiles.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.empty),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        val dir = LocalLayoutDirection.current
        LazyColumn(
            Modifier.fillMaxSize().padding(start = inner.calculateStartPadding(dir), end = inner.calculateEndPadding(dir)),
            contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding() + 88.dp),
        ) {
            items(profiles, key = { it.id }) { p ->
                ServerRow(p, problems[p.id], actions, onEdit)
                HorizontalDivider()
            }
        }
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text(stringResource(R.string.app_name)) },
            text = { Text(stringResource(R.string.about_text, BuildConfig.VERSION_NAME)) },
            confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

@Composable
private fun ServerRow(p: Profile, problem: String?, actions: AppActions, onEdit: (Profile) -> Unit) {
    Column {
        ListItem(
            headlineContent = { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                val sub = if (p.command.isBlank()) p.address else "${p.address} · ${p.command}"
                Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingContent = {
                Icon(painterResource(R.drawable.ic_terminal), null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                IconButton(onClick = { onEdit(p) }) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit)) }
            },
            modifier = Modifier.clickable { actions.connect(p) },
        )
        if (problem == null) return@Column
        // why the last session ended, in place of a dialog
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp)) {
            SelectionContainer {
                Text(
                    problem,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val aanBlocked = p.aan && (problem.contains("dial timed out") || problem.contains("aan"))
            if (aanBlocked) {
                Text(stringResource(R.string.aan_blocked), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { actions.connect(p) }) { Text(stringResource(R.string.reconnect)) }
                if (aanBlocked) {
                    TextButton(onClick = { actions.connectWithoutAan(p) }) { Text(stringResource(R.string.connect_without_aan)) }
                }
                TextButton(onClick = { actions.dismissProblem(p) }) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
}

@Composable
private fun ServerEditor(
    initial: Profile,
    isNew: Boolean,
    hasSavedPassword: Boolean,
    phoneAccess: Boolean,
    actions: AppActions,
    onDuplicate: (Profile) -> Unit,
    onClose: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var cpu by rememberSaveable { mutableStateOf(initial.cpu) }
    var auth by rememberSaveable { mutableStateOf(initial.auth) }
    var user by rememberSaveable { mutableStateOf(initial.user) }
    var password by rememberSaveable { mutableStateOf("") }
    var savePassword by rememberSaveable { mutableStateOf(initial.savePassword) }
    var aan by rememberSaveable { mutableStateOf(initial.aan) }
    var phoneStorage by rememberSaveable { mutableStateOf(initial.phoneStorage) }
    var command by rememberSaveable { mutableStateOf(initial.command) }
    var secstore by rememberSaveable { mutableStateOf(initial.secstore) }
    var encryption by rememberSaveable { mutableStateOf(initial.encryption) }
    var extraArgs by rememberSaveable { mutableStateOf(initial.extraArgs) }
    var scale by rememberSaveable { mutableIntStateOf(initial.scale) }
    var trackpad by rememberSaveable { mutableStateOf(initial.trackpad) }
    var extraKeys by rememberSaveable { mutableStateOf(initial.extraKeys) }
    var fullscreen by rememberSaveable { mutableStateOf(initial.fullscreen) }
    var keepAlive by rememberSaveable { mutableStateOf(initial.keepAlive) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun edited() = initial.copy(
        name = name.trim(), cpu = cpu.trim(), auth = auth.trim(), user = user.trim(),
        savePassword = savePassword, aan = aan, phoneStorage = phoneStorage,
        command = command.trim(), secstore = secstore.trim(), encryption = encryption.trim(),
        extraArgs = extraArgs.trim(), scale = scale, trackpad = trackpad, extraKeys = extraKeys,
        fullscreen = fullscreen, keepAlive = keepAlive,
    )

    // Changes are kept without asking; an entry without a CPU server is dropped.
    fun keep() {
        if (cpu.isNotBlank() && (edited() != initial || password.isNotEmpty())) actions.save(edited(), password)
    }
    fun leave() {
        keep()
        onClose()
    }
    BackHandler(onBack = ::leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isNew) R.string.new_server else R.string.edit_server)) },
                navigationIcon = {
                    IconButton(onClick = ::leave) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = { keep(); onDuplicate(edited()) }) {
                            Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.duplicate))
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete))
                        }
                    }
                    TextButton(enabled = cpu.isNotBlank(), onClick = ::leave) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Field(cpu, { cpu = it }, R.string.field_cpu, R.string.field_cpu_hint, KeyboardType.Uri)
            Field(user, { user = it }, R.string.field_user)
            Field(auth, { auth = it }, R.string.field_auth, R.string.field_auth_hint, KeyboardType.Uri, placeholder = cpu)
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) },
                placeholder = if (hasSavedPassword && savePassword) {
                    { Text(stringResource(R.string.password_saved)) }
                } else {
                    null
                },
                supportingText = {
                    Text(stringResource(if (savePassword) R.string.password_hint_saved else R.string.password_hint_ask))
                },
                enabled = savePassword,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Toggle(R.string.save_password, R.string.save_password_desc, savePassword) { savePassword = it }
            Toggle(R.string.aan, R.string.aan_desc, aan) { aan = it }
            Toggle(R.string.keep_alive, R.string.keep_alive_desc, keepAlive) { keepAlive = it }
            Toggle(
                R.string.phone_storage,
                if (phoneStorage && !phoneAccess) R.string.phone_storage_needs_access else R.string.phone_storage_desc,
                phoneStorage,
            ) {
                phoneStorage = it
                if (it && !phoneAccess) actions.requestPhoneAccess()
            }
            Field(name, { name = it }, R.string.field_name, R.string.field_name_hint, prose = true)

            Text(
                stringResource(R.string.section_screen),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(stringResource(R.string.scale_title), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in 0..4) {
                    FilterChip(
                        selected = scale == s,
                        onClick = { scale = s },
                        label = { Text(if (s == 0) stringResource(R.string.scale_auto_short) else "$s×") },
                    )
                }
            }
            Toggle(R.string.fullscreen, R.string.fullscreen_desc, fullscreen) { fullscreen = it }
            Toggle(R.string.trackpad, R.string.trackpad_desc, trackpad) { trackpad = it }
            Toggle(R.string.extra_keys, R.string.extra_keys_desc, extraKeys) { extraKeys = it }

            TextButton(onClick = { advanced = !advanced }) {
                Text(stringResource(if (advanced) R.string.hide_advanced else R.string.show_advanced))
            }
            if (advanced) {
                Field(command, { command = it }, R.string.field_command, R.string.field_command_hint)
                Field(secstore, { secstore = it }, R.string.field_secstore, R.string.field_secstore_hint, KeyboardType.Uri)
                Field(encryption, { encryption = it }, R.string.field_encryption, R.string.field_encryption_hint)
                Field(extraArgs, { extraArgs = it }, R.string.field_args, R.string.field_args_hint)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title, initial.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.delete(initial)
                    onClose()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    @StringRes label: Int,
    @StringRes hint: Int? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String = "",
    prose: Boolean = false, // a label for people rather than a name for Plan 9
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = if (hint != null) {
            { Text(stringResource(hint)) }
        } else {
            null
        },
        placeholder = if (placeholder.isNotBlank()) {
            { Text(placeholder) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = if (prose) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            autoCorrectEnabled = prose,
            keyboardType = keyboardType,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Toggle(@StringRes title: Int, @StringRes description: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
