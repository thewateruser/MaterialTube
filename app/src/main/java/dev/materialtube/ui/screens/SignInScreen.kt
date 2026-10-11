package dev.materialtube.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.materialtube.Graph
import dev.materialtube.data.auth.TvOAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SignInViewModel : ViewModel() {
    var code by mutableStateOf<TvOAuth.DeviceCode?>(null)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var done by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun start() {
        job?.cancel()
        code = null
        status = "Requesting a sign-in code…"
        job = viewModelScope.launch {
            try {
                val dc = withContext(Dispatchers.IO) { Graph.auth.startDeviceFlow() }
                code = dc
                status = "Waiting for you to approve on Google's page…"
                var interval = dc.intervalSeconds * 1000L
                while (true) {
                    delay(interval)
                    when (val r = withContext(Dispatchers.IO) { Graph.auth.poll(dc) }) {
                        TvOAuth.PollResult.Pending -> Unit
                        TvOAuth.PollResult.SlowDown -> interval += 5000
                        TvOAuth.PollResult.Success -> {
                            Graph.signedIn.value = true
                            status = "Signed in!"
                            done = true
                            return@launch
                        }
                        is TvOAuth.PollResult.Failed -> {
                            status = "${r.reason}. Get a new code to try again."
                            code = null
                            return@launch
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Couldn't reach Google (${e.message}). Check your connection; if you use Private DNS or an ad-blocking DNS/VPN, make sure it doesn't block youtube.com, then get a new code."
            }
        }
    }
}

@Composable
fun SignInScreen(onDone: () -> Unit, onSkip: () -> Unit) {
    val vm: SignInViewModel = viewModel()
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (vm.code == null && !vm.done) vm.start() }
    LaunchedEffect(vm.done) { if (vm.done) onDone() }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Sign in with Google", style = MaterialTheme.typography.displaySmallEmphasized, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        Text(
            "Your home feed, subscriptions, history and likes stay in sync with your YouTube account, " +
                "so recommendations keep working. You approve the sign-in on Google's own page (no MicroG needed), " +
                "and this app never sees your password.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val dc = vm.code
        if (dc != null) {
            Text("1. Copy this code", style = MaterialTheme.typography.titleMedium)
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                SelectionContainer {
                    Text(
                        dc.userCode,
                        fontSize = 34.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                    )
                }
            }
            Text("2. Open Google's page, paste the code, pick your account and allow access", textAlign = TextAlign.Center)
            Button(
                onClick = { openDevicePage(context, dc) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shapes = ButtonDefaults.shapes(),
            ) { Text("Copy code & open google.com/device", style = MaterialTheme.typography.titleMedium) }
            Text(
                "Tip: on another device you can also go to ${dc.verificationUrl} and type the code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            LoadingIndicator()
        } else if (!vm.done) {
            OutlinedButton(onClick = { vm.start() }, shapes = ButtonDefaults.shapes()) { Text("Get a new code") }
        }
        vm.status?.let { Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = onSkip) { Text("Continue without signing in") }
    }
}

private fun openDevicePage(context: Context, dc: TvOAuth.DeviceCode) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Sign-in code", dc.userCode))
    Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
    runCatching {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(dc.verificationUrl))
    }
}
