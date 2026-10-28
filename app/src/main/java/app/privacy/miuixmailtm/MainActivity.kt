package app.privacy.miuixmailtm

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.privacy.miuixmailtm.data.MailTmRepository
import app.privacy.miuixmailtm.data.MessageDetail
import app.privacy.miuixmailtm.service.MailTmForegroundService
import app.privacy.miuixmailtm.util.ClipboardHelper
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        startBackgroundMailService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Every fresh app process run automatically creates a brand-new mail.tm account & copies it
        MailTmRepository.ensureNewRunIdentity(applicationContext)
        requestNotificationPermissionAndStartService()

        setContent {
            val uiState by MailTmRepository.state.collectAsState()

            LaunchedEffect(uiState.secureScreen) {
                if (uiState.secureScreen) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MiuixTheme(colors = colors) {
                MailTmMiuixScreen(
                    onRequestBatteryExemption = { requestBatteryOptimizationExemption() }
                )
            }
        }
    }

    private fun requestNotificationPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startBackgroundMailService()
    }

    private fun startBackgroundMailService() {
        val serviceIntent = Intent(this, MailTmForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(
                    this,
                    "Battery unrestricted mode is already active",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (_: Exception) {
        }
    }
}

@Composable
fun MailTmMiuixScreen(
    onRequestBatteryExemption: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by MailTmRepository.state.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()
    var expandedMessageId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.lastCopyToast) {
        state.lastCopyToast?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "Miuix Mail.tm",
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            )
        ) {
            item(key = "header_identity") {
                SmallTitle(text = "EPHEMERAL IDENTITY (NEW EVERY RUN)")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    BasicComponent(
                        title = state.currentEmail,
                        summary = state.statusLine,
                        onClick = {
                            ClipboardHelper.copyText(context, "Mail.tm Address", state.currentEmail)
                            Toast.makeText(
                                context,
                                "Copied: ${state.currentEmail}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            text = "Copy Mail",
                            onClick = {
                                ClipboardHelper.copyText(context, "Mail.tm Address", state.currentEmail)
                                Toast.makeText(
                                    context,
                                    "Copied: ${state.currentEmail}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = if (state.isProvisioning) "Generating..." else "New Mail",
                            onClick = {
                                if (!state.isProvisioning) {
                                    scope.launch {
                                        MailTmRepository.rotateIdentity(context, "One-Tap Burn")
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item(key = "otp_banner") {
                SmallTitle(text = "INSTANT OTP EXTRACTOR")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    val otpText = state.latestOtp ?: "Waiting for incoming verification code..."
                    BasicComponent(
                        title = if (state.latestOtp != null) "OTP: ${state.latestOtp}" else "No OTP Received Yet",
                        summary = if (state.latestOtp != null) {
                            "Auto-copied to clipboard • Tap to copy again"
                        } else {
                            "Any 4–8 digit verification code will auto-copy on arrival"
                        },
                        onClick = {
                            state.latestOtp?.let { otp ->
                                ClipboardHelper.copyText(context, "Mail.tm OTP", otp)
                                Toast.makeText(context, "Copied OTP: $otp", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            item(key = "automation_prefs") {
                SmallTitle(text = "AUTOMATION & PRIVACY")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    SwitchPreference(
                        title = "Auto-Copy Email Address",
                        summary = "Automatically copy new email on app launch & rotation",
                        checked = state.autoCopyMail,
                        onCheckedChange = { MailTmRepository.setAutoCopyMail(it) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        title = "Auto-Copy Incoming OTP",
                        summary = "Extract & copy 4–8 digit OTP codes in the background",
                        checked = state.autoCopyOtp,
                        onCheckedChange = { MailTmRepository.setAutoCopyOtp(it) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        title = "FLAG_SECURE Privacy Shield",
                        summary = "Block screenshots and hide inbox from Recents preview",
                        checked = state.secureScreen,
                        onCheckedChange = { MailTmRepository.setSecureScreen(it) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    BasicComponent(
                        title = "Unrestricted Background Speed",
                        summary = "Tap to exempt from HyperOS / Android battery throttling",
                        onClick = onRequestBatteryExemption
                    )
                }
            }

            item(key = "inbox_header") {
                SmallTitle(text = "LIVE INBOX (${state.messages.size})")
            }

            if (state.messages.isEmpty()) {
                item(key = "empty_inbox") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        BasicComponent(
                            title = "Inbox is Empty",
                            summary = "Polling api.mail.tm every 2.5s via persistent HTTP/2 connection",
                            onClick = {
                                scope.launch { MailTmRepository.pollMessagesOnce(context) }
                            }
                        )
                    }
                }
            } else {
                items(
                    items = state.messages,
                    key = { it.id }
                ) { message ->
                    MessageItemCard(
                        message = message,
                        isExpanded = expandedMessageId == message.id,
                        onToggleExpand = {
                            expandedMessageId = if (expandedMessageId == message.id) null else message.id
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageItemCard(
    message: MessageDetail,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        BasicComponent(
            title = message.subject.ifBlank { "(No Subject)" },
            summary = "From: ${message.from.address} • Tap to ${if (isExpanded) "collapse" else "read full message"}",
            onClick = onToggleExpand
        )
        if (isExpanded) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = message.text ?: message.intro,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(
                    text = "Copy Full Message Body",
                    onClick = {
                        val body = message.text ?: message.intro
                        ClipboardHelper.copyText(context, "Mail.tm Message", body)
                        Toast.makeText(context, "Copied message body", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
