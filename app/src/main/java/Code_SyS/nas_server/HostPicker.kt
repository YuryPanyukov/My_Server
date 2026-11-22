package Code_SyS.nas_server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Выбор недавнего телефона: один и тот же список в «Поделиться» и в панели
 * выделения, чтобы не держать две копии разметки.
 */
@Composable
internal fun HostPickerDialog(
    title: String,
    hosts: List<ShareTarget>,
    onPick: (ShareTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.recent_hosts_section),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                hosts.forEach { host ->
                    ShareOption(
                        title = "${host.host}:${host.port}",
                        subtitle = androidx.compose.ui.res.stringResource(R.string.send_wifi),
                        onClick = { onPick(host) },
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(R.string.cancel_button))
            }
        },
    )
}

/** Строка выбора в диалогах отправки. */
@Composable
internal fun ShareOption(title: String, subtitle: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = scheme.surfaceVariant.copy(alpha = .45f),
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = .55f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(scheme.primary.copy(alpha = .14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("↑", fontSize = 14.sp, color = scheme.primary)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 10.5.sp, color = scheme.onSurfaceVariant)
            }
            Text("›", fontSize = 18.sp, color = scheme.onSurfaceVariant)
        }
    }
}
