package br.com.banzo.fiscalasfalto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import android.graphics.BitmapFactory
import android.location.Location
import android.location.Geocoder
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import br.com.banzo.fiscalasfalto.data.AppDatabase
import br.com.banzo.fiscalasfalto.data.Occurrence
import br.com.banzo.fiscalasfalto.data.OccurrenceStatus
import br.com.banzo.fiscalasfalto.data.Pathologies
import br.com.banzo.fiscalasfalto.sync.GraphSyncWorker
import br.com.banzo.fiscalasfalto.util.PdfReport
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val db by lazy { AppDatabase.get(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleSync()
        setContent {
            val banzoPeach = androidx.compose.ui.graphics.Color(0xFFF9C493)
            val banzoBlack = androidx.compose.ui.graphics.Color(0xFF1D1917)
            MaterialTheme(
                colorScheme = androidx.compose.material3.lightColorScheme(
                    primary = banzoBlack,
                    onPrimary = androidx.compose.ui.graphics.Color.White,
                    secondary = banzoPeach,
                    onSecondary = banzoBlack,
                    tertiary = banzoPeach,
                    background = androidx.compose.ui.graphics.Color(0xFFF7F5F2),
                    surface = androidx.compose.ui.graphics.Color.White,
                    onSurface = banzoBlack,
                    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFEDE8E3),
                    outline = androidx.compose.ui.graphics.Color(0xFFB8AEA6)
                ),
                shapes = androidx.compose.material3.Shapes(
                    small = RoundedCornerShape(10.dp),
                    medium = RoundedCornerShape(14.dp),
                    large = RoundedCornerShape(20.dp)
                )
            ) { InspectionApp() }
        }
    }

    private fun scheduleSync() {
        val request = PeriodicWorkRequestBuilder<GraphSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("onedrive_sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun InspectionApp() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var occurrences by remember { mutableStateOf(emptyList<Occurrence>()) }
        var inspector by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var reference by remember { mutableStateOf("") }
        var pathology by remember { mutableStateOf(Pathologies.options.first()) }
        var other by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var lat by remember { mutableStateOf("") }
        var lon by remember { mutableStateOf("") }
        var expanded by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf(OccurrenceStatus.options.first()) }
        var workOrder by remember { mutableStateOf("") }
        var photos by remember { mutableStateOf("") }
        var pendingPhotoPath by remember { mutableStateOf("") }
        var locationPending by remember { mutableStateOf(false) }
        var selectedPhotoPath by remember { mutableStateOf<String?>(null) }
        var screen by remember { mutableStateOf("home") }
        var formStep by remember { mutableStateOf(0) }
        var selectedOccurrence by remember { mutableStateOf<Occurrence?>(null) }
        var editingId by remember { mutableStateOf<String?>(null) }
        var searchQuery by remember { mutableStateOf("") }
        var reportFiles by remember { mutableStateOf(emptyList<File>()) }
        var reportToDelete by remember { mutableStateOf<File?>(null) }
        var occurrenceToDelete by remember { mutableStateOf<Occurrence?>(null) }
        fun refreshReports() {
            val dir = File(getExternalFilesDir(null) ?: filesDir, "relatorios")
            reportFiles = dir.listFiles { f -> f.isFile && f.extension.equals("pdf", true) }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        }

        // Obtém uma posição atual (não depende apenas de uma localização antiga em cache).
        fun applyCapturedLocation(location: Location?) {
            if (location == null) {
                locationPending = false
                Toast.makeText(context, "Ainda não foi possível obter uma posição. Tente novamente em local aberto.", Toast.LENGTH_LONG).show()
                return
            }
            lat = String.format(Locale.US, "%.6f", location.latitude)
            lon = String.format(Locale.US, "%.6f", location.longitude)
            Toast.makeText(context, "Coordenadas capturadas. Buscando endereço…", Toast.LENGTH_SHORT).show()
            scope.launch {
                val resolvedAddress = withContext(Dispatchers.IO) {
                    try {
                        val geocoder = Geocoder(context, Locale("pt", "BR"))
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(location.latitude, location.longitude, 1)
                            ?.firstOrNull()?.getAddressLine(0)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (!resolvedAddress.isNullOrBlank()) {
                    // O campo permanece editável para correções feitas pelo fiscal.
                    address = resolvedAddress
                    Toast.makeText(context, "GPS e endereço preenchidos. Você pode corrigir o endereço.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "GPS capturado, mas o endereço automático não foi localizado. Você pode preenchê-lo manualmente.", Toast.LENGTH_LONG).show()
                }
                locationPending = false
            }
        }


        fun fetchCurrentLocation() {
            val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) {
                locationPending = false
                Toast.makeText(context, "Permita o acesso à localização para capturar o GPS.", Toast.LENGTH_LONG).show()
                return
            }

            locationPending = true
            val client = LocationServices.getFusedLocationProviderClient(this@MainActivity)
            val token = CancellationTokenSource()
            try {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
                    .addOnSuccessListener { current ->
                        if (current != null) {
                            applyCapturedLocation(current)
                        } else {
                            client.lastLocation
                                .addOnSuccessListener { last -> applyCapturedLocation(last) }
                                .addOnFailureListener {
                                    locationPending = false
                                    Toast.makeText(context, "Não foi possível obter a localização. Confira o GPS e tente novamente em local aberto.", Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                    .addOnFailureListener {
                        client.lastLocation
                            .addOnSuccessListener { last -> applyCapturedLocation(last) }
                            .addOnFailureListener {
                                locationPending = false
                                Toast.makeText(context, "Falha ao obter a localização. Confira o GPS e tente novamente.", Toast.LENGTH_LONG).show()
                            }
                    }
            } catch (_: SecurityException) {
                locationPending = false
                Toast.makeText(context, "Sem permissão de localização. Confira as permissões do aplicativo.", Toast.LENGTH_LONG).show()
            }
        }

        val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) fetchCurrentLocation()
            else {
                locationPending = false
                Toast.makeText(context, "Permissão de localização negada. Ative-a nas configurações do aplicativo.", Toast.LENGTH_LONG).show()
            }
        }

        val photoCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val capturedPath = pendingPhotoPath
            pendingPhotoPath = ""
            if (success && capturedPath.isNotBlank() && File(capturedPath).exists() && File(capturedPath).length() > 0L) {
                photos = (photos.split("\n").filter { it.isNotBlank() } + capturedPath).joinToString("\n")
                Toast.makeText(context, "Foto adicionada à ocorrência", Toast.LENGTH_SHORT).show()
            } else if (!success) {
                capturedPath.takeIf { it.isNotBlank() }?.let { File(it).delete() }
                Toast.makeText(context, "A captura foi cancelada ou não foi concluída. Tente novamente.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "A câmera não salvou a fotografia. Tente novamente.", Toast.LENGTH_LONG).show()
            }
        }

        fun launchPhotoCapture() {
            try {
                val dir = File(getExternalFilesDir("photos") ?: filesDir, "occurrences")
                if (!dir.exists() && !dir.mkdirs()) {
                    Toast.makeText(context, "Não foi possível preparar a pasta das fotografias.", Toast.LENGTH_LONG).show()
                    return
                }
                val file = File(dir, "foto_${System.currentTimeMillis()}.jpg")
                val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "${packageName}.files", file)
                pendingPhotoPath = file.absolutePath
                photoCapture.launch(uri)
            } catch (e: Exception) {
                pendingPhotoPath = ""
                Toast.makeText(context, "Não foi possível abrir a câmera: ${e.localizedMessage ?: "erro inesperado"}", Toast.LENGTH_LONG).show()
            }
        }

        val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchPhotoCapture()
            else Toast.makeText(context, "Permissão da câmera negada. Para fotografar, permita o acesso à câmera nas configurações do Banzo Vistoria.", Toast.LENGTH_LONG).show()
        }
        LaunchedEffect(Unit) { db.occurrenceDao().observeAll().collectLatest { occurrences = it } }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (screen in listOf("home", "occurrences", "reports", "settings")) {
                    NavigationBar(containerColor = androidx.compose.ui.graphics.Color.White) {
                        NavigationBarItem(selected = screen == "home", onClick = { screen = "home" }, icon = { Text("⌂") }, label = { Text("Início") })
                        NavigationBarItem(selected = screen == "occurrences", onClick = { screen = "occurrences" }, icon = { Text("▤") }, label = { Text("Ocorrências") })
                        NavigationBarItem(selected = screen == "reports", onClick = { refreshReports(); screen = "reports" }, icon = { Text("▧") }, label = { Text("Relatórios") })
                        NavigationBarItem(selected = screen == "settings", onClick = { screen = "settings" }, icon = { Text("⚙") }, label = { Text("Ajustes") })
                    }
                }
            },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF1D1917),
                        titleContentColor = androidx.compose.ui.graphics.Color.White
                    ),
                    title = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(modifier = Modifier.size(38.dp).background(androidx.compose.ui.graphics.Color(0xFFF9C493), RoundedCornerShape(11.dp)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Text("B", style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color(0xFF1D1917))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("BANZO VISTORIA", style = MaterialTheme.typography.titleMedium)
                                Text("ENGENHARIA • FISCALIZAÇÃO TÉCNICA", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color(0xFFF9C493))
                            }
                        }
                    }
                )
            }
        ) { padding ->
            when (screen) {
                "home" -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF1D1917)), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("GESTÃO DE CAMPO  /  01", style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color(0xFFF9C493))
                                Text("Painel de fiscalização", style = MaterialTheme.typography.headlineSmall, color = androidx.compose.ui.graphics.Color.White)
                                Text("Registre evidências, acompanhe pendências e gere relatórios técnicos em um só lugar.", style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color(0xFFE8E2DD))
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Box(Modifier.size(8.dp).background(androidx.compose.ui.graphics.Color(0xFF8BC49A), RoundedCornerShape(50)))
                                    Text("Dados salvos localmente neste aparelho", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color(0xFFE8E2DD))
                                }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Card(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("TOTAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${occurrences.size}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                                    Text("Ocorrências", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Card(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFF9C493))) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("PENDENTES", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color(0xFF514239))
                                    Text("${occurrences.count { it.status != "Corrigida e verificada" }}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                                    Text("Em acompanhamento", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color(0xFF514239), maxLines = 1)
                                }
                            }
                            Card(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFE7F1E8))) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("CONCLUÍDAS", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color(0xFF386548))
                                    Text("${occurrences.count { it.status == "Corrigida e verificada" }}", style = MaterialTheme.typography.headlineMedium, color = androidx.compose.ui.graphics.Color(0xFF245B39))
                                    Text("Verificadas", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color(0xFF386548))
                                }
                            }
                        }
                    }
                    item { Text("Acesso rápido", style = MaterialTheme.typography.titleLarge) }
                    item {
                        Button(onClick = { editingId = null; formStep = 0; inspector = ""; address = ""; reference = ""; pathology = Pathologies.options.first(); other = ""; description = ""; lat = ""; lon = ""; photos = ""; workOrder = ""; status = OccurrenceStatus.options.first(); screen = "form" }, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("＋  Nova vistoria", style = MaterialTheme.typography.titleMedium)
                                Text("Registrar uma ocorrência em campo", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color(0xFFE9E5E1))
                            }
                        }
                    }
                    item {
                        Card(onClick = { screen = "occurrences" }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Box(Modifier.size(42.dp).background(androidx.compose.ui.graphics.Color(0xFFF0ECE8), RoundedCornerShape(12.dp)), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("01", style = MaterialTheme.typography.titleMedium) }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text("Ocorrências", style = MaterialTheme.typography.titleMedium); Text("Pesquisar, editar e acompanhar registros", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text("›", style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                    }
                    item {
                        Card(onClick = { refreshReports(); screen = "reports" }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Box(Modifier.size(42.dp).background(androidx.compose.ui.graphics.Color(0xFFF9C493), RoundedCornerShape(12.dp)), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("PDF", style = MaterialTheme.typography.labelLarge) }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text("Central de relatórios", style = MaterialTheme.typography.titleMedium); Text("Gerar, abrir e compartilhar documentos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text("›", style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Últimas ocorrências", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { screen = "occurrences" }) { Text("Ver todas") }
                        }
                    }
                    if (occurrences.isEmpty()) item {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("Nenhuma vistoria registrada", style = MaterialTheme.typography.titleMedium)
                                Text("As ocorrências cadastradas aparecerão aqui para consulta rápida.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(occurrences.take(3), key = { it.id }) { item ->
                        Card(onClick = { selectedOccurrence = item; screen = "detail" }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(item.id, style = MaterialTheme.typography.titleSmall); Spacer(Modifier.weight(1f)); Text(item.status.uppercase(Locale("pt", "BR")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                                Text(item.address.ifBlank { "Endereço não informado" }, style = MaterialTheme.typography.bodyMedium)
                                Text(item.pathology, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                "reports" -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Button(onClick = { screen = "home" }) { Text("← Painel inicial") } }
                    item { Text("Central de relatórios", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("Os PDFs são armazenados no aparelho. Excluir um PDF não exclui a ocorrência.", style = MaterialTheme.typography.bodySmall) }
                    item { Button(onClick = { scope.launch {
                        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }
                        val start = cal.timeInMillis; cal.add(Calendar.DAY_OF_MONTH,1)
                        val list = db.occurrenceDao().forPeriod(start, cal.timeInMillis)
                        val file = PdfReport.createDaily(context, list)
                        refreshReports()
                        sharePdf(file)
                    } }, modifier = Modifier.fillMaxWidth()) { Text("Gerar relatório diário de hoje") } }
                    item { Button(onClick = { scope.launch {
                        val file = PdfReport.createDaily(context, occurrences)
                        refreshReports(); sharePdf(file)
                    } }, modifier = Modifier.fillMaxWidth()) { Text("Gerar relatório de todas as ocorrências") } }
                    if (reportFiles.isEmpty()) item { Text("Nenhum PDF salvo ainda.", modifier = Modifier.padding(8.dp)) }
                    items(reportFiles, key = { it.absolutePath }) { file ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(file.name, style = MaterialTheme.typography.titleSmall)
                            Text("${file.length() / 1024} KB • ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(file.lastModified()))}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { openPdf(file) }) { Text("Abrir") }
                                Button(onClick = { sharePdf(file) }) { Text("Compartilhar") }
                                Button(onClick = { reportToDelete = file }) { Text("Excluir PDF") }
                            }
                        } }
                    }
                }
                "occurrences" -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Button(onClick = { screen = "home" }) { Text("← Painel inicial") } }
                    item { Text("Ocorrências registradas", style = MaterialTheme.typography.headlineSmall) }
                    item { OutlinedTextField(searchQuery, { searchQuery = it }, label = { Text("Buscar por ID, endereço, OS ou patologia") }, modifier = Modifier.fillMaxWidth()) }
                    val filtered = occurrences.filter { listOf(it.id,it.address,it.workOrderNumber,it.pathology,it.inspector).any { value -> value.contains(searchQuery, ignoreCase = true) } }
                    if (filtered.isEmpty()) item { Text("Nenhuma ocorrência encontrada.") }
                    items(filtered, key = { it.id }) { item ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.id, style = MaterialTheme.typography.titleMedium)
                            Text(item.address.ifBlank { "Endereço não informado" })
                            Text("${item.pathology} • ${item.status}")
                            if (item.workOrderNumber.isNotBlank()) Text("OS: ${item.workOrderNumber}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { selectedOccurrence = item; screen = "detail" }) { Text("Detalhes") }
                                Button(onClick = {
                                    editingId = item.id; formStep = 0; inspector = item.inspector; address = item.address; reference = item.reference
                                    lat = item.latitude?.toString() ?: ""; lon = item.longitude?.toString() ?: ""
                                    pathology = item.pathology; other = item.pathologyOther; description = item.description
                                    photos = item.photoPaths; workOrder = item.workOrderNumber; status = item.status; screen = "form"
                                }) { Text("Editar") }
                                Button(onClick = { scope.launch { sharePdf(PdfReport.createSingle(context, item)) } }) { Text("PDF") }
                                Button(onClick = { occurrenceToDelete = item }) { Text("Excluir") }
                            }
                        } }
                    }
                }
                "detail" -> {
                    val selected = selectedOccurrence
                    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item { TextButton(onClick = { screen = "occurrences" }) { Text("← Voltar às ocorrências") } }
                        if (selected != null) {
                            item {
                                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF1D1917))) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("FICHA DE VISTORIA", style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color(0xFFF9C493))
                                        Text(selected.id, style = MaterialTheme.typography.headlineSmall, color = androidx.compose.ui.graphics.Color.White)
                                        Text(selected.status, style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color(0xFFF9C493))
                                    }
                                }
                            }
                            item { Text("Identificação", style = MaterialTheme.typography.titleMedium) }
                            item { DetailLine("Fiscal responsável", selected.inspector) }
                            item { DetailLine("Endereço", selected.address.ifBlank { "Não informado" }) }
                            item { DetailLine("Ponto de referência", selected.reference.ifBlank { "Não informado" }) }
                            item { DetailLine("Ordem de serviço", selected.workOrderNumber.ifBlank { "Não informada" }) }
                            item { Text("Localização", style = MaterialTheme.typography.titleMedium) }
                            item { DetailLine("Latitude", selected.latitude?.toString() ?: "Não registrada") }
                            item { DetailLine("Longitude", selected.longitude?.toString() ?: "Não registrada") }
                            if (selected.latitude != null && selected.longitude != null) {
                                item { MapPreviewCard(selected.latitude, selected.longitude) {
                                    val mapUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${selected.latitude},${selected.longitude}")
                                    try { startActivity(Intent(Intent.ACTION_VIEW, mapUri)) } catch (_: Exception) { Toast.makeText(context, "Não foi possível abrir o aplicativo de mapas.", Toast.LENGTH_LONG).show() }
                                } }
                            }
                            item { Text("Avaliação técnica", style = MaterialTheme.typography.titleMedium) }
                            item { DetailLine("Patologia", selected.pathology + if (selected.pathology == "Outra — especificar") ": ${selected.pathologyOther}" else "") }
                            item { DetailLine("Descrição / providência", selected.description.ifBlank { "Não informada" }) }
                            item { Text("Evidências fotográficas", style = MaterialTheme.typography.titleMedium) }
                            item {
                                val paths = selected.photoPaths.split("\n").filter { it.isNotBlank() }
                                if (paths.isEmpty()) Text("Nenhuma fotografia anexada.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    paths.forEachIndexed { index, path ->
                                        val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                                        if (bitmap != null) Image(bitmap, "Fotografia ${index + 1}", Modifier.size(112.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                                    }
                                }
                            }
                            item { Button(onClick = { editingId = selected.id; inspector = selected.inspector; address = selected.address; reference = selected.reference; lat = selected.latitude?.toString() ?: ""; lon = selected.longitude?.toString() ?: ""; pathology = selected.pathology; other = selected.pathologyOther; description = selected.description; photos = selected.photoPaths; workOrder = selected.workOrderNumber; status = selected.status; formStep = 0; screen = "form" }, modifier = Modifier.fillMaxWidth()) { Text("Editar vistoria") } }
                            item { Button(onClick = { scope.launch { sharePdf(PdfReport.createSingle(context, selected)) } }, modifier = Modifier.fillMaxWidth()) { Text("Gerar relatório PDF desta ocorrência") } }
                        }
                    }
                }
                "settings" -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Text("Ajustes", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("Configurações do ambiente de fiscalização", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Dados do aplicativo", style = MaterialTheme.typography.titleMedium); DetailLine("Aplicativo", "Banzo Vistoria"); DetailLine("Versão do layout", "0.4.2 • proposta profissional"); DetailLine("Armazenamento", "Banco local no aparelho") } } }
                    item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Relatórios e documentos", style = MaterialTheme.typography.titleMedium); Text("Os PDFs ficam na pasta privada do aplicativo. Excluir um PDF não remove o registro da ocorrência.", style = MaterialTheme.typography.bodySmall); Button(onClick = { refreshReports(); screen = "reports" }, modifier = Modifier.fillMaxWidth()) { Text("Abrir central de relatórios") } } } }
                    item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Sincronização", style = MaterialTheme.typography.titleMedium); Text("A sincronização real com OneDrive depende da configuração da conta Microsoft e do Microsoft Graph.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { TextButton(onClick = { screen = if (editingId == null) "home" else "occurrences" }) { Text("← Cancelar e voltar") } }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(if (editingId == null) "Nova vistoria técnica" else "Editar vistoria", style = MaterialTheme.typography.headlineSmall)
                            Text("Etapa ${formStep + 1} de 4 • Registro técnico de campo", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf("Identificação", "Localização", "Avaliação", "Evidências").forEachIndexed { index, label ->
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Box(Modifier.fillMaxWidth().heightIn(min = 5.dp).background(if (index <= formStep) androidx.compose.ui.graphics.Color(0xFFF9C493) else androidx.compose.ui.graphics.Color(0xFFE2DDD8), RoundedCornerShape(20.dp)))
                                    Text(label, style = MaterialTheme.typography.labelSmall, color = if (index == formStep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (formStep == 0) {
                        item { SectionIntro("01", "Identificação da vistoria", "Informe quem realizou a fiscalização e onde a ocorrência foi identificada.") }
                        item { OutlinedTextField(inspector, { inspector = it }, label = { Text("Fiscal responsável *") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                        item { OutlinedTextField(address, { address = it }, label = { Text("Endereço *") }, modifier = Modifier.fillMaxWidth()) }
                        item { OutlinedTextField(reference, { reference = it }, label = { Text("Ponto de referência") }, modifier = Modifier.fillMaxWidth()) }
                        item { OutlinedTextField(workOrder, { workOrder = it }, label = { Text("Número da ordem de serviço") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    }
                    if (formStep == 1) {
                        item { SectionIntro("02", "Localização", "Registre as coordenadas do local vistoriado. O endereço automático pode ser corrigido manualmente.") }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, modifier = Modifier.weight(1f), singleLine = true); OutlinedTextField(lon, { lon = it }, label = { Text("Longitude") }, modifier = Modifier.weight(1f), singleLine = true) } }
                        item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFE8F3EA))) { Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("⌖", style = MaterialTheme.typography.headlineMedium, color = androidx.compose.ui.graphics.Color(0xFF247746)); Column { Text(if (lat.isNotBlank() && lon.isNotBlank()) "Coordenadas registradas" else "GPS ainda não capturado", style = MaterialTheme.typography.titleSmall); Text(if (lat.isNotBlank() && lon.isNotBlank()) "$lat, $lon" else "Use o botão abaixo para obter a posição atual.", style = MaterialTheme.typography.bodySmall) } } } }
                        if (lat.toDoubleOrNull() != null && lon.toDoubleOrNull() != null) {
                            item { MapPreviewCard(lat.toDouble(), lon.toDouble()) {
                                val mapUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${lat.toDoubleOrNull()},${lon.toDoubleOrNull()}")
                                try { startActivity(Intent(Intent.ACTION_VIEW, mapUri)) } catch (_: Exception) { Toast.makeText(context, "Não foi possível abrir o aplicativo de mapas.", Toast.LENGTH_LONG).show() }
                            } }
                        }
                        item { Button(onClick = { val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED; val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED; if (fine || coarse) fetchCurrentLocation() else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, enabled = !locationPending, modifier = Modifier.fillMaxWidth()) { Text(if (locationPending) "Obtendo localização…" else "⌖  Capturar coordenadas GPS e endereço") } }
                        item { OutlinedTextField(address, { address = it }, label = { Text("Endereço identificado") }, modifier = Modifier.fillMaxWidth()) }
                    }
                    if (formStep == 2) {
                        item { SectionIntro("03", "Avaliação técnica", "Descreva a patologia observada e a providência necessária. Evite conclusões sem evidência.") }
                        item { ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) { OutlinedTextField(value = pathology, onValueChange = {}, readOnly = true, label = { Text("Patologia identificada *") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth()); ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { Pathologies.options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { pathology = option; expanded = false }) } } } }
                        if (pathology == "Outra — especificar") item { OutlinedTextField(other, { other = it }, label = { Text("Especifique a patologia *") }, modifier = Modifier.fillMaxWidth()) }
                        item { OutlinedTextField(description, { description = it }, label = { Text("Descrição técnica / providência solicitada *") }, minLines = 5, modifier = Modifier.fillMaxWidth()) }
                        item { var statusExpanded by remember { mutableStateOf(false) }; ExposedDropdownMenuBox(expanded = statusExpanded, onExpandedChange = { statusExpanded = !statusExpanded }) { OutlinedTextField(status, {}, readOnly = true, label = { Text("Situação da ocorrência") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth()); ExposedDropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) { OccurrenceStatus.options.forEach { opt -> DropdownMenuItem(text = { Text(opt) }, onClick = { status = opt; statusExpanded = false }) } } } }
                    }
                    if (formStep == 3) {
                        item { SectionIntro("04", "Evidências e conclusão", "Registre fotografias do local e revise os dados antes de salvar a ocorrência.") }
                        item {
                            val photoPaths = photos.split("\n").filter { it.isNotBlank() }
                            Text("Evidências fotográficas • ${photoPaths.size}", style = MaterialTheme.typography.titleMedium)
                            if (photoPaths.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) { photoPaths.forEachIndexed { index, path -> val thumbnail = remember(path) { val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(path, bounds); var sample = 1; while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) sample *= 2; BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }; if (thumbnail != null) Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) { Image(thumbnail, "Fotografia ${index + 1}", Modifier.size(100.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop); Text("Foto ${index + 1}", style = MaterialTheme.typography.labelSmall) } } }
                            Button(onClick = { val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; if (granted) launchPhotoCapture() else cameraPermission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) { Text("▣  Fotografar local") }
                        }
                        item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("Resumo da vistoria", style = MaterialTheme.typography.titleMedium); DetailLine("Fiscal", inspector.ifBlank { "Não informado" }); DetailLine("Endereço", address.ifBlank { "Não informado" }); DetailLine("Patologia", pathology); DetailLine("Ordem de serviço", workOrder.ifBlank { "Não informada" }); DetailLine("Status", status) } } }
                        item { Button(onClick = { if (inspector.isBlank() || address.isBlank() || description.isBlank()) { Toast.makeText(context, "Preencha fiscal, endereço e descrição técnica antes de salvar.", Toast.LENGTH_LONG).show(); return@Button }; if (pathology == "Outra — especificar" && other.isBlank()) { Toast.makeText(context, "Descreva a patologia selecionada como Outra.", Toast.LENGTH_SHORT).show(); return@Button }; scope.launch { val now = System.currentTimeMillis(); val existing = editingId?.let { id -> occurrences.firstOrNull { it.id == id } }; val id = existing?.id ?: "RA-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now))}-${UUID.randomUUID().toString().take(4).uppercase(Locale.US)}"; val item = Occurrence(id, existing?.createdAt ?: now, inspector.trim(), address.trim(), reference.trim(), lat.toDoubleOrNull(), lon.toDoubleOrNull(), pathology, other.trim(), description.trim(), photos.trim().replace(";", "\n"), workOrder.trim(), status, existing?.synced ?: false); db.occurrenceDao().upsert(item); editingId = null; inspector = ""; address = ""; reference = ""; pathology = Pathologies.options.first(); other = ""; description = ""; lat = ""; lon = ""; photos = ""; workOrder = ""; status = OccurrenceStatus.options.first(); formStep = 0; screen = "occurrences"; Toast.makeText(context, "Ocorrência ${if (existing == null) "salva" else "atualizada"}: $id", Toast.LENGTH_LONG).show() } }, modifier = Modifier.fillMaxWidth()) { Text(if (editingId == null) "Salvar ocorrência" else "Salvar alterações") } }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (formStep > 0) androidx.compose.material3.OutlinedButton(onClick = { formStep -= 1 }, modifier = Modifier.weight(1f)) { Text("Voltar") }
                            if (formStep < 3) Button(onClick = { if (formStep == 0 && (inspector.isBlank() || address.isBlank())) Toast.makeText(context, "Informe o fiscal e o endereço para continuar.", Toast.LENGTH_SHORT).show() else if (formStep == 2 && description.isBlank()) Toast.makeText(context, "Inclua a descrição técnica para continuar.", Toast.LENGTH_SHORT).show() else formStep += 1 }, modifier = Modifier.weight(1f)) { Text("Próximo  →") }
                        }
                    }
                }
            }
        }
        if (reportToDelete != null) AlertDialog(onDismissRequest = { reportToDelete = null }, title = { Text("Excluir relatório?") }, text = { Text("O PDF será removido do aparelho. A ocorrência continuará salva.") }, confirmButton = { Button(onClick = { reportToDelete?.delete(); reportToDelete = null; refreshReports() }) { Text("Excluir PDF") } }, dismissButton = { Button(onClick = { reportToDelete = null }) { Text("Cancelar") } })
        if (occurrenceToDelete != null) AlertDialog(onDismissRequest = { occurrenceToDelete = null }, title = { Text("Excluir ocorrência?") }, text = { Text("A ocorrência ${occurrenceToDelete?.id} será removida do banco local. Essa ação não pode ser desfeita.") }, confirmButton = { Button(onClick = { val item = occurrenceToDelete; occurrenceToDelete = null; if (item != null) scope.launch { db.occurrenceDao().deleteById(item.id); Toast.makeText(context, "Ocorrência excluída", Toast.LENGTH_SHORT).show() } }) { Text("Excluir ocorrência") } }, dismissButton = { Button(onClick = { occurrenceToDelete = null }) { Text("Cancelar") } })
    }


    @Composable
    private fun MapPreviewCard(latitude: Double, longitude: Double, onOpenMap: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Localização da vistoria", style = MaterialTheme.typography.titleMedium)
                Text("Prévia do mapa • OpenStreetMap", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp, max = 190.dp),
                        factory = { viewContext ->
                            WebView(viewContext).apply {
                                settings.javaScriptEnabled = false
                                settings.domStorageEnabled = false
                                settings.loadsImagesAutomatically = true
                                webViewClient = WebViewClient()
                                val left = longitude - 0.0025
                                val right = longitude + 0.0025
                                val bottom = latitude - 0.0018
                                val top = latitude + 0.0018
                                loadUrl("https://www.openstreetmap.org/export/embed.html?bbox=$left%2C$bottom%2C$right%2C$top&layer=mapnik&marker=$latitude%2C$longitude")
                            }
                        },
                        update = { view ->
                            val left = longitude - 0.0025
                            val right = longitude + 0.0025
                            val bottom = latitude - 0.0018
                            val top = latitude + 0.0018
                            val mapUrl = "https://www.openstreetmap.org/export/embed.html?bbox=$left%2C$bottom%2C$right%2C$top&layer=mapnik&marker=$latitude%2C$longitude"
                            if (view.url != mapUrl) view.loadUrl(mapUrl)
                        }
                    )
                    Button(onClick = onOpenMap, modifier = Modifier.fillMaxWidth()) { Text("Abrir localização no Google Maps") }
                    Text("A miniatura exige conexão com a internet. As coordenadas continuam registradas no aparelho.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Capture ou informe coordenadas válidas para visualizar o mapa.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    @Composable
    private fun SectionIntro(number: String, title: String, description: String) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            Text("ETAPA $number", style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color(0xFF9A5B26))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun DetailLine(label: String, value: String) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
            Text(label.uppercase(Locale("pt", "BR")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFFE9E3DD))
        }
    }

    private fun openPdf(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startActivity(Intent.createChooser(intent, "Abrir relatório PDF"))
        } catch (_: Exception) { Toast.makeText(this, "Não foi encontrado um aplicativo para abrir PDF.", Toast.LENGTH_LONG).show() }
    }

    private fun sharePdf(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Banzo Vistoria — ${file.nameWithoutExtension}")
                putExtra(Intent.EXTRA_TEXT, "Segue em anexo o relatório gerado pelo Banzo Vistoria.")
                clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Enviar relatório por e-mail ou compartilhar"))
        } catch (_: Exception) { Toast.makeText(this, "Não foi encontrado um aplicativo compatível para compartilhar o PDF.", Toast.LENGTH_LONG).show() }
    }

}
