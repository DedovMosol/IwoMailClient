package com.iwo.mailclient.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.iwo.mailclient.ui.theme.AppIcons
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iwo.mailclient.ui.Strings
import kotlinx.coroutines.*

/**
 * Состояние удаления
 */
data class DeletionState(
    val isActive: Boolean = false,
    val totalCount: Int = 0,
    val deletedCount: Int = 0,
    val progress: Float = 0f,
    val message: String = "",
    val phase: DeletionPhase = DeletionPhase.COUNTDOWN
)

enum class DeletionPhase {
    COUNTDOWN,  // Обратный отсчёт (можно отменить)
    DELETING    // Реальное удаление (нельзя отменить)
}

/**
 * Контроллер удаления с реальным прогрессом
 * Использует собственный scope чтобы удаление продолжалось даже при выходе с экрана
 */
class DeletionController {
    var state by mutableStateOf(DeletionState())
        private set
    
    private var deletionJob: Job? = null
    private var isCancelled = false
    
    // Собственный scope для удаления (не зависит от UI)
    private val deletionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    /**
     * Запускает удаление с обратным отсчётом
     * @param emailIds список ID писем для удаления
     * @param message сообщение для отображения
     * @param onDelete функция удаления с callback прогресса
     */
    fun startDeletion(
        emailIds: List<String>,
        message: String,
        scope: CoroutineScope, // Игнорируется, используем собственный scope
        onDelete: suspend (List<String>, onProgress: (deleted: Int, total: Int) -> Unit) -> Unit
    ) {
        // Отменяем предыдущее удаление если было
        cancel()
        isCancelled = false
        
        state = DeletionState(
            isActive = true,
            totalCount = emailIds.size,
            deletedCount = 0,
            progress = 0f,
            message = message,
            phase = DeletionPhase.COUNTDOWN
        )
        
        // Обратный отсчёт: 3 секунды (можно отменить)
        val countdownMs = 3000L
        val stepMs = 50L
        val steps = (countdownMs / stepMs).toInt()
        
        // Используем собственный scope чтобы удаление не прерывалось при навигации
        deletionJob = deletionScope.launch {
            try {
                // Фаза 1: Обратный отсчёт
                for (i in 1..steps) {
                    if (isCancelled) return@launch
                    delay(stepMs)
                    state = state.copy(progress = i.toFloat() / steps)
                }
                
                if (isCancelled) return@launch
                
                // Фаза 2: Реальное удаление
                state = state.copy(
                    phase = DeletionPhase.DELETING,
                    progress = 0f,
                    deletedCount = 0
                )
                
                // Выполняем удаление с callback прогресса
                onDelete(emailIds) { deleted, total ->
                    if (!isCancelled) {
                        state = state.copy(
                            deletedCount = deleted,
                            progress = if (total > 0) deleted.toFloat() / total else 1f
                        )
                    }
                }
                
                // Завершено
                delay(500)
            } finally {
                state = DeletionState()
            }
        }
    }
    
    /**
     * Отменяет удаление (только в фазе обратного отсчёта)
     */
    fun cancel() {
        if (state.phase == DeletionPhase.COUNTDOWN) {
            isCancelled = true
            deletionJob?.cancel()
            deletionJob = null
            state = DeletionState()
        }
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
    
    if (state.isActive) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = 80.dp), // Отступ от FAB
            color = MaterialTheme.colorScheme.errorContainer,
            shadowElevation = 8.dp,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
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
                            text = when (state.phase) {
                                DeletionPhase.COUNTDOWN -> "${(state.progress * 100).toInt()}%"
                                DeletionPhase.DELETING -> "${state.deletedCount} / ${state.totalCount}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                        )
                    }
                    
                    // Кнопка отмены только в фазе обратного отсчёта
                    if (state.phase == DeletionPhase.COUNTDOWN) {
                        TextButton(
                            onClick = { controller.cancel() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(
                                AppIcons.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(Strings.cancel)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.error,
                    trackColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f)
                )
            }
        }
    }
}
