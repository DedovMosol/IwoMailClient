package com.exchange.mailclient.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.exchange.mailclient.ui.Strings
import com.exchange.mailclient.ui.theme.LocalAnimationsEnabled
import kotlinx.coroutines.*

/**
 * Состояние отложенного удаления
 */
data class DeletionState(
    val isActive: Boolean = false,
    val emailIds: List<String> = emptyList(),
    val progress: Float = 0f,
    val message: String = ""
)

/**
 * Контроллер отложенного удаления
 */
class DeletionController {
    var state by mutableStateOf(DeletionState())
        private set
    
    private var deletionJob: Job? = null
    private var onDeleteConfirmed: (suspend (List<String>) -> Unit)? = null
    
    /**
     * Запускает отложенное удаление
     * @param emailIds список ID писем для удаления
     * @param message сообщение для отображения
     * @param onConfirmed callback который вызывается когда удаление подтверждено (прогресс завершён)
     */
    fun startDeletion(
        emailIds: List<String>,
        message: String,
        scope: CoroutineScope,
        onConfirmed: suspend (List<String>) -> Unit
    ) {
        // Отменяем предыдущее удаление если было
        cancel()
        
        onDeleteConfirmed = onConfirmed
        state = DeletionState(
            isActive = true,
            emailIds = emailIds,
            progress = 0f,
            message = message
        )
        
        // Время зависит от количества писем: минимум 2 сек, максимум 8 сек
        // 100мс на письмо, но не меньше 2000мс и не больше 8000мс
        val totalTimeMs = (emailIds.size * 100L).coerceIn(2000L, 8000L)
        val stepMs = 50L // Обновляем прогресс каждые 50мс
        val steps = (totalTimeMs / stepMs).toInt()
        
        deletionJob = scope.launch {
            for (i in 1..steps) {
                delay(stepMs)
                state = state.copy(progress = i.toFloat() / steps)
            }
            
            // Прогресс завершён — выполняем реальное удаление
            state = state.copy(progress = 1f)
            onDeleteConfirmed?.invoke(emailIds)
            
            // Небольшая задержка перед скрытием
            delay(300)
            state = DeletionState()
            onDeleteConfirmed = null
        }
    }
    
    /**
     * Отменяет удаление
     */
    fun cancel() {
        deletionJob?.cancel()
        deletionJob = null
        state = DeletionState()
        onDeleteConfirmed = null
    }
}

/**
 * CompositionLocal для доступа к контроллеру удаления из любого места
 */
val LocalDeletionController = compositionLocalOf { DeletionController() }

/**
 * Плашка прогресса удаления
 */
@Composable
fun DeletionProgressBar(
    controller: DeletionController,
    modifier: Modifier = Modifier
) {
    val state = controller.state
    val animationsEnabled = LocalAnimationsEnabled.current
    
    AnimatedVisibility(
        visible = state.isActive,
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                        )
                    }
                    
                    TextButton(
                        onClick = { controller.cancel() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(Strings.cancel)
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Прогресс-бар
                if (animationsEnabled) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.error,
                        trackColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f)
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.error,
                        trackColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}
