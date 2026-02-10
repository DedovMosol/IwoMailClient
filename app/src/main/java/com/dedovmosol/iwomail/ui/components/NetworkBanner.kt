package com.dedovmosol.iwomail.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.network.rememberNetworkState
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons

/**
 * Баннер "Нет сети" — показывается в верхней части экрана когда нет подключения
 */
@Composable
fun NetworkBanner(
    modifier: Modifier = Modifier
) {
    val isConnected by rememberNetworkState()
    
    AnimatedVisibility(
        visible = !isConnected,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                AppIcons.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = Strings.noNetwork,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
