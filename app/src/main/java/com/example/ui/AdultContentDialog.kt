package com.example.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * One-time age confirmation shown before the first 18+ voice style can be used.
 * The confirmation is persisted by [com.example.data.SiamVoicePreferences.confirmAdultContent].
 */
@Composable
fun AdultContentDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("adult_dialog"),
        title = { Text("เนื้อหา 18+ (18+ Content)") },
        text = {
            Text(
                "สไตล์เสียงเหล่านี้มีน้ำเสียงกวน ปากจัด หรือเย้ายวนสำหรับผู้ใหญ่ " +
                    "เหมาะสำหรับผู้ที่มีอายุ 18 ปีขึ้นไปเท่านั้น\n\n" +
                    "18+ content. I confirm I am 18 years of age or older."
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("adult_confirm_button")
            ) {
                Text("ฉันยืนยันว่าอายุ 18 ปีขึ้นไป")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("adult_cancel_button")
            ) {
                Text("ยกเลิก")
            }
        }
    )
}
