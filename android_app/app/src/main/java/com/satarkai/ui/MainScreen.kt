package com.satarkai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.satarkai.*
import com.satarkai.R

enum class SatarkTab(val label: String) {
    Contacts("Contacts"),
    Recents("Recents"),
    Keypad("Keypad"),
    Settings("SIP Line")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    currentTab: SatarkTab,
    onTabSelected: (SatarkTab) -> Unit,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean) -> Unit,
    isSipOnline: Boolean,
    sipStatusText: String,
    isServerOnline: Boolean,
    serverDiagnostics: String,
    serverUrl: String,
    onSaveServerUrl: (String) -> Unit,
    onTestServer: (String) -> Unit,
    onResetServerUrl: () -> Unit,
    onRegisterSip: (String, String) -> Unit,
    contacts: List<ContactItem>,
    onSearchContacts: (String) -> Unit,
    onCallContact: (ContactItem) -> Unit,
    history: List<CallHistoryRecord>,
    onSelectHistoryItem: (CallHistoryRecord) -> Unit,
    onPlaceCall: (String) -> Unit,
    onQuickCall: (String, String, String, Boolean) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "SatarkAI Logo",
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Column {
                            Text(
                                text = "SatarkAI",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "AI Telephony & Vishing Defense",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Server connection pill
                    AssistChip(
                        onClick = { onTabSelected(SatarkTab.Settings) },
                        label = {
                            Text(
                                text = if (isServerOnline) "Server" else "Offline",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isServerOnline) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isServerOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (isServerOnline) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                            labelColor = if (isServerOnline) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.padding(end = 4.dp)
                    )

                    // SIP connection pill
                    AssistChip(
                        onClick = { onTabSelected(SatarkTab.Settings) },
                        label = {
                            Text(
                                text = if (isSipOnline) "SIP" else "Unlinked",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isSipOnline) Icons.Default.CheckCircle else Icons.Default.PhonePaused,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isSipOnline) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (isSipOnline) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = if (isSipOnline) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == SatarkTab.Contacts,
                    onClick = { onTabSelected(SatarkTab.Contacts) },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == SatarkTab.Contacts) Icons.Filled.Contacts else Icons.Outlined.Contacts,
                            contentDescription = "Contacts"
                        )
                    },
                    label = { Text("Contacts") }
                )
                NavigationBarItem(
                    selected = currentTab == SatarkTab.Recents,
                    onClick = { onTabSelected(SatarkTab.Recents) },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == SatarkTab.Recents) Icons.Filled.History else Icons.Outlined.History,
                            contentDescription = "Recents"
                        )
                    },
                    label = { Text("Recents") }
                )
                NavigationBarItem(
                    selected = currentTab == SatarkTab.Keypad,
                    onClick = { onTabSelected(SatarkTab.Keypad) },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == SatarkTab.Keypad) Icons.Filled.Dialpad else Icons.Outlined.Dialpad,
                            contentDescription = "Keypad"
                        )
                    },
                    label = { Text("Keypad") }
                )
                NavigationBarItem(
                    selected = currentTab == SatarkTab.Settings,
                    onClick = { onTabSelected(SatarkTab.Settings) },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == SatarkTab.Settings) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "SIP Line"
                        )
                    },
                    label = { Text("SIP Line") }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (currentTab) {
                SatarkTab.Contacts -> ContactsTabContent(
                    contacts = contacts,
                    onSearch = onSearchContacts,
                    onCallContact = onCallContact
                )
                SatarkTab.Recents -> RecentsTabContent(
                    history = history,
                    onSelectRecord = onSelectHistoryItem
                )
                SatarkTab.Keypad -> KeypadTabContent(
                    onPlaceCall = onPlaceCall,
                    onQuickCall = onQuickCall
                )
                SatarkTab.Settings -> SettingsTabContent(
                    isDarkMode = isDarkMode,
                    onToggleDarkMode = onToggleDarkMode,
                    sipStatusText = sipStatusText,
                    isSipOnline = isSipOnline,
                    onRegisterSip = onRegisterSip,
                    serverUrl = serverUrl,
                    serverDiagnostics = serverDiagnostics,
                    isServerOnline = isServerOnline,
                    onSaveServerUrl = onSaveServerUrl,
                    onTestServer = onTestServer,
                    onResetServerUrl = onResetServerUrl
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsTabContent(
    contacts: List<ContactItem>,
    onSearch: (String) -> Unit,
    onCallContact: (ContactItem) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = searchQuery,
            onQueryChange = {
                searchQuery = it
                onSearch(it)
            },
            onSearch = { onSearch(it) },
            active = false,
            onActiveChange = {},
            placeholder = { Text("Search verified directory or SIP ID...") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Search")
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = {
                        searchQuery = ""
                        onSearch("")
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {}

        Text(
            text = "VERIFIED DIRECTORY (${contacts.size})",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No contacts found matching search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(contacts, key = { it.id }) { contact ->
                    ContactRow(
                        contact = contact,
                        onCall = { onCallContact(contact) }
                    )
                }
            }
        }
    }
}

@Composable
fun ContactRow(
    contact: ContactItem,
    onCall: () -> Unit
) {
    var avatarBgColor by remember(contact.avatarColorHex) {
        val parsed = try {
            Color(android.graphics.Color.parseColor(contact.avatarColorHex))
        } catch (_: Exception) {
            Color(0xFF0D9488)
        }
        mutableStateOf(parsed)
    }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onCall),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                if (contact.isVerified) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Verified Directory",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        supportingContent = {
            Text(
                text = "${contact.handleOrPhone} • ${contact.roleOrType}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(avatarBgColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.avatarInitials,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        },
        trailingContent = {
            FilledIconButton(
                onClick = onCall,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = "Call Contact"
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
fun RecentsTabContent(
    history: List<CallHistoryRecord>,
    onSelectRecord: (CallHistoryRecord) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "FORENSIC CALL LOGS (${history.size})",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )

        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No calls recorded yet.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "All outgoing and incoming calls will be logged with full ASR transcripts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(history, key = { it.callSessionId }) { record ->
                    HistoryRow(
                        record = record,
                        onClick = { onSelectRecord(record) }
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryRow(
    record: CallHistoryRecord,
    onClick: () -> Unit
) {
    val directionIcon = when {
        record.isMissed -> Icons.Default.CallMissed
        record.isIncoming -> Icons.Default.CallReceived
        else -> Icons.Default.CallMade
    }

    val iconTint = when {
        record.isMissed -> MaterialTheme.colorScheme.error
        record.status == CallSecurityStatus.SCAM -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = directionIcon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        },
        headlineContent = {
            Text(
                text = record.contactName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        },
        supportingContent = {
            Text(
                text = "${record.timestampFormatted} • ${record.durationFormatted}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            when (record.status) {
                CallSecurityStatus.SCAM -> {
                    val pct = if (record.scamConfidence > 0) " ${(record.scamConfidence * 100).toInt()}%" else ""
                    SuggestionChip(
                        onClick = onClick,
                        label = {
                            Text(
                                text = "SCAM$pct",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            labelColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = MaterialTheme.colorScheme.error
                        )
                    )
                }
                CallSecurityStatus.SAFE -> {
                    SuggestionChip(
                        onClick = onClick,
                        label = {
                            Text(
                                text = "SAFE",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
                CallSecurityStatus.UNVERIFIED -> {
                    SuggestionChip(
                        onClick = onClick,
                        label = {
                            Text(
                                text = "UNVERIFIED",
                                fontWeight = FontWeight.Normal,
                                fontSize = 11.sp
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
fun KeypadTabContent(
    onPlaceCall: (String) -> Unit,
    onQuickCall: (String, String, String, Boolean) -> Unit
) {
    var dialNumber by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Smart SIP Dialer",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )

                OutlinedTextField(
                    value = dialNumber,
                    onValueChange = { dialNumber = it },
                    label = { Text("Enter SIP extension or phone number") },
                    placeholder = { Text("e.g. 1001 or sip:target@domain") },
                    leadingIcon = {
                        Icon(Icons.Default.Phone, contentDescription = null)
                    },
                    trailingIcon = {
                        if (dialNumber.isNotEmpty()) {
                            IconButton(onClick = { dialNumber = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (dialNumber.isNotBlank()) {
                            onPlaceCall(dialNumber.trim())
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Place Monitored Call", fontWeight = FontWeight.Bold)
                }
            }
        }

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Quick Simulation Lines",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Test real-time ASR, Whisper latency, and IndicBERT threats against simulated targets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ElevatedButton(
                    onClick = {
                        onQuickCall(
                            "sip:sbi-support@sip.linphone.org",
                            "State Bank of India (Official)",
                            "SB",
                            false
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.AccountBalance,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Test Bank Line (Expected Safe)")
                }

                ElevatedButton(
                    onClick = {
                        onQuickCall(
                            "sip:scam-test@sip.linphone.org",
                            "Suspicious Caller (+91 98765 43210)",
                            "!",
                            true
                        )
                    },
                    colors = ButtonDefaults.elevatedButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Test Vishing Attack (Scam Pretext)")
                }
            }
        }
    }
}

@Composable
fun SettingsTabContent(
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean) -> Unit,
    sipStatusText: String,
    isSipOnline: Boolean,
    onRegisterSip: (String, String) -> Unit,
    serverUrl: String,
    serverDiagnostics: String,
    isServerOnline: Boolean,
    onSaveServerUrl: (String) -> Unit,
    onTestServer: (String) -> Unit,
    onResetServerUrl: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var currentUrl by remember(serverUrl) { mutableStateOf(serverUrl) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Theme Settings Card
        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                ListItem(
                    headlineContent = {
                        Text("Dark Theme", fontWeight = FontWeight.SemiBold)
                    },
                    supportingContent = {
                        Text("Switch between dynamic dark and light mode")
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = isDarkMode,
                            onCheckedChange = onToggleDarkMode,
                            thumbContent = {
                                Icon(
                                    imageVector = if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    }
                )
            }
        }

        // SIP Extension Card
        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SIP Line Registration",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text(sipStatusText) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (isSipOnline) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = if (isSipOnline) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("SIP Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("SIP Password") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "Toggle password visibility"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = { onRegisterSip(username, password) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Register SIP Account")
                    }
                }
            }
        }

        // Server Backend Configuration Card
        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AI Server Backend",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        AssistChip(
                            onClick = { onTestServer(currentUrl) },
                            label = { Text(if (isServerOnline) "Online" else "Offline") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (isServerOnline) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                                labelColor = if (isServerOnline) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        )
                    }

                    OutlinedTextField(
                        value = currentUrl,
                        onValueChange = { currentUrl = it },
                        label = { Text("Server Base URL") },
                        placeholder = { Text("http://192.168.1.100:8000") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (serverDiagnostics.isNotEmpty()) {
                        Text(
                            text = serverDiagnostics,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onTestServer(currentUrl) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Test")
                        }
                        Button(
                            onClick = { onSaveServerUrl(currentUrl) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { currentUrl = ServerConfigManager.DEFAULT_EMULATOR_URL },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Emulator")
                        }
                        OutlinedButton(
                            onClick = onResetServerUrl,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Reset")
                        }
                    }
                }
            }
        }
    }
}
