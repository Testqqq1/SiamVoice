package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SecureApiKeyStore
import com.example.model.GeminiModelPreference
import com.example.model.VoiceStylePreset
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.GoldAccent
import com.example.ui.theme.IndigoDark
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.RoseError
import com.example.ui.theme.SkyBlue
import com.example.ui.theme.SurfaceCard

@Composable
fun SettingsScreen(
    viewModel: SiamVoiceViewModel,
    onNavigateBack: () -> Unit
) {
    AdvancedSettingsScreen(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    viewModel: SiamVoiceViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    if (state.showAdultDialog) {
        AdultContentDialog(
            onConfirm = { viewModel.confirmAdultContent() },
            onDismiss = { viewModel.dismissAdultDialog() }
        )
    }

    BackHandler {
        onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "ตั้งค่า (Settings)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "กลับสู่หน้าหลัก"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Section 1: Gemini API Key (BYOK)
            SectionHeader(title = "1. Gemini API Key (BYOK)")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (state.hasStoredApiKey) Icons.Default.CheckCircle else Icons.Default.Key,
                                contentDescription = null,
                                tint = if (state.hasStoredApiKey) EmeraldGreen else GoldAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (state.hasStoredApiKey) {
                                    "บันทึกคีย์แล้ว (${state.maskedApiKey})"
                                } else {
                                    "ยังไม่ได้ตั้งค่า API Key"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (state.hasStoredApiKey) EmeraldGreen else GoldAccent,
                                modifier = Modifier.testTag("api_key_status_text")
                            )
                        }

                        TextButton(
                            onClick = { viewModel.openAiStudioApiKeyPage(context) },
                            modifier = Modifier.testTag("open_aistudio_keys_button")
                        ) {
                            Text(
                                text = "รับคีย์ที่ AI Studio",
                                fontSize = 11.sp,
                                color = SkyBlue,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "เปิด aistudio.google.com/api-keys",
                                tint = SkyBlue,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "รับคีย์ได้จาก ${SecureApiKeyStore.OFFICIAL_API_KEY_PORTAL_URL}\n" +
                                "หมายเหตุ: คีย์ถูกเข้ารหัสด้วย Android Keystore สำหรับการใช้งานส่วนบุคคล (BYOK) และส่งไปยังเซิร์ฟเวอร์ทางการของ Google เท่านั้น การใช้งานอาจใช้โควตาหรือมีค่าใช้จ่ายตามบัญชี Google AI Studio ของคุณ",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = state.apiKeyInput,
                        onValueChange = { viewModel.onApiKeyInputChanged(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("api_key_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        placeholder = {
                            Text(
                                text = if (state.hasStoredApiKey) {
                                    "กรอกคีย์ใหม่เพื่อเปลี่ยน (${state.maskedApiKey})"
                                } else {
                                    "วาง Gemini API Key (AIza...)"
                                },
                                fontSize = 12.sp
                            )
                        },
                        visualTransformation = if (state.isApiKeyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { viewModel.toggleApiKeyVisibility() },
                                modifier = Modifier.testTag("toggle_api_key_visibility_button")
                            ) {
                                Icon(
                                    imageVector = if (state.isApiKeyVisible) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = if (state.isApiKeyVisible) "ซ่อน API Key" else "แสดง API Key"
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IndigoPrimary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.saveApiKey() },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_api_key_button"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                        ) {
                            Text(
                                text = if (state.hasStoredApiKey) "เปลี่ยนคีย์" else "บันทึกคีย์",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = { viewModel.testApiConnection() },
                            enabled = !state.isTestingConnection,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("test_api_key_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (state.isTestingConnection) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = "ทดสอบการเชื่อมต่อ",
                                    fontSize = 12.sp
                                )
                            }
                        }

                        if (state.hasStoredApiKey) {
                            OutlinedButton(
                                onClick = { viewModel.deleteApiKey() },
                                modifier = Modifier.testTag("delete_api_key_button"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "ลบ API Key",
                                    tint = RoseError,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    state.apiKeyErrorMessage?.let { err ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = err,
                            color = RoseError,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.testTag("api_key_error_message")
                        )
                    }

                    state.apiKeyStatusMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = msg,
                            color = EmeraldGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.testTag("api_key_status_message")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 2: Gemini Model
            SectionHeader(title = "2. โมเดลเสียง Gemini (Gemini Model)")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    GeminiModelPreference.entries.forEach { pref ->
                        val isSelected = state.settings.modelPreference == pref
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { viewModel.selectModelPreference(pref) }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                                .testTag("model_pref_${pref.name.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.selectModelPreference(pref) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = pref.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = pref.subtitle,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(8.dp))

                    DiagRow(
                        label = "Primary Flash Model",
                        value = state.verifiedModelsStatus.flashModelId,
                        valueColor = GoldAccent
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    DiagRow(
                        label = "Secondary Flash-Lite Model",
                        value = state.verifiedModelsStatus.flashLiteModelId,
                        valueColor = SkyBlue
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: 18+ voice styles (off by default, one-time age confirmation)
            SectionHeader(title = "สไตล์เสียง 18+ (Adult Voice Styles)")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "เปิดใช้สไตล์เสียง 18+",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = VoiceStylePreset.adultPresets.joinToString(" • ") { it.presetName } +
                                    "\nปิดอยู่เป็นค่าเริ่มต้น ต้องยืนยันว่าอายุ 18 ปีขึ้นไป " +
                                    "และจะปิดคำลงท้าย ครับ/ค่ะ อัตโนมัติในสไตล์เหล่านี้",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = state.settings.adultContentConfirmed,
                            onCheckedChange = { enabled ->
                                if (enabled) viewModel.requestAdultContentUnlock() else viewModel.revokeAdultContent()
                            },
                            modifier = Modifier.testTag("adult_content_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = IndigoPrimary
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 3: Android TTS Fallback
            SectionHeader(title = "3. ระบบเสียงสำรอง Android TTS (Android TTS Fallback)")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "สลับไปใช้ Android TTS อัตโนมัติ",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "ทำงานทันทีเมื่อไม่มี API Key, ออฟไลน์, โควตาเต็ม หรือเครือข่ายขัดข้อง",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = state.settings.autoFallbackEnabled,
                            onCheckedChange = { viewModel.toggleAutoFallback(it) },
                            modifier = Modifier.testTag("auto_fallback_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = IndigoPrimary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "เครื่องยนต์เสียง Android ภายนอก (ไม่เรียกตัวเองซ้ำ):",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (state.availableEngines.isEmpty()) {
                        Text(
                            text = "ใช้เครื่องยนต์ TTS ภายนอกของระบบที่รองรับภาษาไทย (th-TH)",
                            fontSize = 12.sp,
                            color = GoldAccent
                        )
                    } else {
                        state.availableEngines.forEach { engine ->
                            val isSelected = state.settings.preferredAndroidEngine == engine.packageName ||
                                    (state.settings.preferredAndroidEngine.isEmpty() && engine.isSystemDefault)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { viewModel.selectPreferredEngine(engine.packageName) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.selectPreferredEngine(engine.packageName) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = engine.label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = engine.packageName + if (engine.isSystemDefault) " (ค่าเริ่มต้น)" else "",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Speech Rate Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ความเร็วการพูด (Speech Rate)", fontSize = 12.sp)
                        Text(
                            text = "%.2fx".format(state.settings.fallbackSpeechRate),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = GoldAccent
                        )
                    }
                    Slider(
                        value = state.settings.fallbackSpeechRate,
                        onValueChange = { viewModel.updateFallbackRate(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        modifier = Modifier.testTag("rate_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = GoldAccent,
                            activeTrackColor = GoldAccent
                        )
                    )

                    // Pitch Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ระดับเสียง (Pitch)", fontSize = 12.sp)
                        Text(
                            text = "%.2fx".format(state.settings.speechPitch),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = SkyBlue
                        )
                    }
                    Slider(
                        value = state.settings.speechPitch,
                        onValueChange = { viewModel.updateSpeechPitch(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        modifier = Modifier.testTag("pitch_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = SkyBlue,
                            activeTrackColor = SkyBlue
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 4: Clear Audio Cache & Basic Diagnostics
            SectionHeader(title = "4. แคชเสียงและสถานะระบบ (Cache & Diagnostics)")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    DiagRow(
                        label = "สถานะ Gemini API Key",
                        value = if (state.hasStoredApiKey) "ตั้งค่าแล้ว (${state.maskedApiKey})" else "ไม่ได้ตั้งค่า (Fallback Mode)",
                        valueColor = if (state.hasStoredApiKey) EmeraldGreen else GoldAccent
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    DiagRow(
                        label = "สถานะเครือข่าย",
                        value = if (state.diagnostics.isOnline) "ออนไลน์ (Online)" else "ออฟไลน์ (Offline)",
                        valueColor = if (state.diagnostics.isOnline) EmeraldGreen else RoseError
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    DiagRow(
                        label = "เครื่องยนต์ที่ทำงานล่าสุด",
                        value = state.diagnostics.activeEngine?.displayName ?: "ยังไม่มีการสังเคราะห์",
                        valueColor = IndigoPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "แคชเสียงในตัวเครื่อง",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${state.diagnostics.cacheItemsCount} รายการ",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.clearCache() },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("clear_cache_button")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ล้างแคชเสียง", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { viewModel.openSystemTtsSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoDark)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ตั้งค่า Text-to-Speech ของระบบ Android")
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun DiagRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}
