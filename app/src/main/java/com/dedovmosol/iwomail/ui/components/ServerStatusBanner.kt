package com.dedovmosol.iwomail.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.model.AccountServerHealth
import com.dedovmosol.iwomail.data.model.ServerProblemType
import com.dedovmosol.iwomail.data.repository.AccountServerHealthRepository
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons

@Composable
fun ServerStatusBanner(
    accountId: Long?,
    onRetry: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (accountId == null) return

    val health by AccountServerHealthRepository.healthFlow(accountId)
        .collectAsState()

    AnimatedVisibility(
        visible = health.showBanner,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        ServerStatusContent(
            health = health,
            onRetry = onRetry,
            onDetails = onDetails
        )
    }
}

@Composable
private fun ServerStatusContent(
    health: AccountServerHealth,
    onRetry: () -> Unit,
    onDetails: () -> Unit
) {
    val isError = health.isCritical
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }

    val icon = resolveIcon(health.problemType)
    val title = resolveTitle(health)
    val staleHint = if (health.isStaleData) Strings.dataMayBeOutdated else null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        if (staleHint != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = staleHint,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = Strings.connectionDetails,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.8f),
                modifier = Modifier
                    .clickable(onClick = onDetails)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = Strings.retrySync,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                modifier = Modifier
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun resolveIcon(type: ServerProblemType): ImageVector = when (type) {
    ServerProblemType.AuthError -> AppIcons.Lock
    ServerProblemType.CertError -> AppIcons.Lock
    else -> AppIcons.Warning
}

@Composable
private fun resolveTitle(health: AccountServerHealth): String {
    val server = health.serverDisplayName
    return when (health.problemType) {
        ServerProblemType.PrimaryDownNoFallback ->
            if (server != null) "${Strings.serverUnavailable}: $server"
            else Strings.serverUnavailable
        ServerProblemType.BothServersDown -> Strings.bothServersUnavailable
        ServerProblemType.AuthError -> Strings.authErrorServer
        ServerProblemType.CertError -> Strings.certErrorServer
        ServerProblemType.ServerError -> Strings.serverError5xx
        ServerProblemType.Timeout -> Strings.serverTimeout
        else -> Strings.serverUnavailable
    }
}
