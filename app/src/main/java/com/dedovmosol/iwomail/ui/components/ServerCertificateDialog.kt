package com.dedovmosol.iwomail.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.network.HttpClientProvider.ServerCertificate
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog
import com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton

/**
 * ONB-08: сертификат сервера, который система не подтвердила, — на сверку с администратором по отпечатку.
 * «Доверять» закрепляет ключ этого сертификата; «Отмена» ничего не меняет. Отпечатки можно выделить и
 * скопировать. SHA-1 показан, потому что именно его Exchange и IIS выводят как Thumbprint.
 */
@Composable
fun ServerCertificateDialog(
    certificate: ServerCertificate,
    serverLabel: String,
    onTrust: () -> Unit,
    onDismiss: () -> Unit
) {
    val now = remember { System.currentTimeMillis() }
    val dateFormat = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
    val info = certificate.info
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.certTrustTitle, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(serverLabel, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(Strings.certTrustExplain, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start)
                Spacer(Modifier.height(12.dp))
                CertificateField(Strings.certIssuedTo, info.cn)
                CertificateField(Strings.certIssuedBy, if (certificate.selfSigned) Strings.certSelfSigned else certificate.issuerName)
                val validity = "${dateFormat.format(java.util.Date(info.validFrom))} – ${dateFormat.format(java.util.Date(info.validTo))}"
                val validityState = when {
                    now > info.validTo -> Strings.certExpired
                    now < info.validFrom -> Strings.certNotYetValid
                    else -> null
                }
                CertificateField(Strings.certValidity, validity, warning = validityState)
                if (certificate.dnsNames.isNotEmpty()) {
                    CertificateField(Strings.certNames, certificate.dnsNames.take(6).joinToString(", "))
                }
                SelectionContainer {
                    Column {
                        CertificateField("SHA-256", certificate.sha256, monospace = true)
                        CertificateField(Strings.certSha1Hint, certificate.sha1, monospace = true)
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
        confirmButton = { ThemeOutlinedButton(onClick = onTrust, text = Strings.certTrustAction) }
    )
}

@Composable
private fun CertificateField(label: String, value: String, monospace: Boolean = false, warning: String? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null
        )
        if (warning != null) {
            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
