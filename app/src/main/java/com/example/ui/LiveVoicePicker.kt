package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.GeminiLiveVoice
import com.example.ui.theme.GoldAccent
import com.example.ui.theme.IndigoPrimary

/**
 * Bottom sheet listing the Gemini-app voice names (like the "Gemini's voice" picker) with a preview
 * button for each. The first row restores the default voice of the selected style.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveVoicePickerSheet(
    selected: GeminiLiveVoice?,
    previewing: GeminiLiveVoice?,
    previewBusy: Boolean,
    previewMessage: String?,
    onSelect: (GeminiLiveVoice?) -> Unit,
    onPreview: (GeminiLiveVoice) -> Unit,
    onStopPreview: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "เลือกเสียง (Gemini Voice)",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "ชื่อเสียงตามแอป Gemini ใช้แทนเสียงเริ่มต้นของสไตล์ (โหมดนำทางใช้เสียงนำทางชัดเจนเสมอ) " +
                    "หมายเหตุ: Google ไม่ได้เปิดเผยว่าชื่อเหล่านี้ตรงกับเสียงใดใน Gemini API จึงเป็นการเทียบโดยประมาณ " +
                    "จากระดับเสียง/อารมณ์ที่ Google ระบุ",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (previewMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = previewMessage,
                    fontSize = 11.sp,
                    color = GoldAccent,
                    modifier = Modifier.testTag("live_voice_preview_message")
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    VoiceRow(
                        title = "ค่าเริ่มต้นของสไตล์",
                        subtitle = "ใช้เสียงที่กำหนดไว้กับสไตล์ที่เลือก",
                        isSelected = selected == null,
                        tag = "live_voice_default",
                        onClick = { onSelect(null) },
                        trailing = null
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
                items(GeminiLiveVoice.entries.toList(), key = { it.name }) { voice ->
                    val isPreviewing = previewing == voice
                    VoiceRow(
                        title = voice.displayName,
                        subtitle = voice.thaiLabel + "  ·  ≈ API: ${voice.apiVoice} (${voice.apiTraits})  ·  โดยประมาณ",
                        isSelected = selected == voice,
                        tag = "live_voice_${voice.name.lowercase()}",
                        onClick = { onSelect(voice) },
                        trailing = {
                            IconButton(
                                onClick = { if (isPreviewing) onStopPreview() else onPreview(voice) },
                                modifier = Modifier.testTag("live_voice_preview_${voice.name.lowercase()}")
                            ) {
                                Icon(
                                    imageVector = if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = if (isPreviewing) {
                                        if (previewBusy) "กำลังสร้างเสียงตัวอย่าง" else "หยุด"
                                    } else "ฟังตัวอย่างเสียง ${voice.displayName}",
                                    tint = IndigoPrimary
                                )
                            }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun VoiceRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    tag: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isSelected) {
            Icon(Icons.Default.CheckCircle, contentDescription = "เลือกอยู่", tint = GoldAccent)
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(4.dp))
            trailing()
        }
    }
}
