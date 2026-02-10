package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme

/**
 * Экран предложения добавить ещё одну учётную запись
 * Показывается после успешного добавления первого аккаунта
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAnotherAccountScreen(
    onAddAccount: () -> Unit,
    onSkip: () -> Unit
) {
    val colorTheme = LocalColorTheme.current
    val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isRussian) "Добавить другую учётную запись" 
                        else "Add another account",
                        color = Color.White
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.background(
                    Brush.horizontalGradient(
                        colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                    )
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Центральный контент
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Иконка с аватарами (как в Outlook)
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Первый аватар (основной)
                    Surface(
                        modifier = Modifier.size(64.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = colorTheme.gradientStart
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "✓",
                                fontSize = 28.sp,
                                color = Color.White
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Второй аватар (пустой/серый)
                    Surface(
                        modifier = Modifier
                            .size(48.dp)
                            .offset(x = (-16).dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = Color.LightGray.copy(alpha = 0.5f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "+",
                                fontSize = 24.sp,
                                color = Color.Gray
                            )
                        }
                    }
                    
                    // Третий аватар (ещё меньше)
                    Surface(
                        modifier = Modifier
                            .size(36.dp)
                            .offset(x = (-24).dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = Color(0xFFFFB74D).copy(alpha = 0.7f)
                    ) {}
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Text(
                    text = if (isRussian) "Добавить ещё одну учётную запись?" 
                           else "Add another account?",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = if (isRussian) 
                        "Вы можете добавить несколько учётных записей для удобного управления почтой"
                    else 
                        "You can add multiple accounts for convenient email management",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Кнопки внизу
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onSkip) {
                    Text(
                        if (isRussian) "Возможно, позднее" else "Maybe later",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                TextButton(onClick = onAddAccount) {
                    Text(
                        if (isRussian) "Добавить" else "Add",
                        color = colorTheme.gradientStart
                    )
                }
            }
        }
    }
}
