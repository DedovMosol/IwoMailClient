package com.exchange.mailclient.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.exchange.mailclient.data.database.EmailEntity
import com.exchange.mailclient.data.repository.AccountRepository
import com.exchange.mailclient.data.repository.MailRepository
import com.exchange.mailclient.eas.EasResult
import com.exchange.mailclient.ui.AppLanguage
import com.exchange.mailclient.ui.LocalLanguage
import com.exchange.mailclient.ui.NotificationStrings
import com.exchange.mailclient.ui.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DateFilter(val days: Int?) {
    ALL(null), TODAY(1), WEEK(7), MONTH(30), YEAR(365)
}

// Цвета для аватаров как в Gmail — стабильные для каждой буквы
private val avatarColors = listOf(
    // Красные/розовые
    Color(0xFFE53935), // Red
    Color(0xFFD81B60), // Pink
    Color(0xFFC2185B), // Pink Dark
    // Фиолетовые
    Color(0xFF8E24AA), // Purple
    Color(0xFF7B1FA2), // Purple Dark
    Color(0xFF5E35B1), // Deep Purple
    Color(0xFF512DA8), // Deep Purple Dark
    // Синие
    Color(0xFF3949AB), // Indigo
    Color(0xFF303F9F), // Indigo Dark
    Color(0xFF1E88E5), // Blue
    Color(0xFF1976D2), // Blue Dark
    Color(0xFF039BE5), // Light Blue
    Color(0xFF0288D1), // Light Blue Dark
    // Голубые/бирюзовые
    Color(0xFF00ACC1), // Cyan
    Color(0xFF0097A7), // Cyan Dark
    Color(0xFF00897B), // Teal
    Color(0xFF00796B), // Teal Dark
    // Зелёные
    Color(0xFF43A047), // Green
    Color(0xFF388E3C), // Green Dark
    Color(0xFF7CB342), // Light Green
    Color(0xFF689F38), // Light Green Dark
    // Жёлтые/оранжевые
    Color(0xFFC0CA33), // Lime
    Color(0xFFAFB42B), // Lime Dark
    Color(0xFFFDD835), // Yellow
    Color(0xFFFBC02D), // Yellow Dark
    Color(0xFFFFB300), // Amber
    Color(0xFFFFA000), // Amber Dark
    Color(0xFFFB8C00), // Orange
    Color(0xFFF57C00), // Orange Dark
    Color(0xFFF4511E), // Deep Orange
    Color(0xFFE64A19), // Deep Orange Dark
    // Коричневые/серые
    Color(0xFF6D4C41), // Brown
    Color(0xFF5D4037), // Brown Dark
    Color(0xFF546E7A), // Blue Grey
    Color(0xFF455A64)  // Blue Grey Dark
)

/**
 * Генерирует стабильный цвет для аватара на основе имени/email
 * Одинаковые имена всегда получают одинаковый цвет
 */
private fun getAvatarColor(name: String): Color {
    if (name.isBlank()) return avatarColors[0]
    // Используем хэш от имени для стабильного цвета
    val hash = name.lowercase().hashCode()
    val index = (hash and 0x7FFFFFFF) % avatarColors.size
    return avatarColors[index]
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBackClick: () -> Unit,
    onEmailClick: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mailRepo = remember { MailRepository(context) }
    val accountRepo = remember { AccountRepository(context) }
    val currentLanguage = LocalLanguage.current
    val isRussian = currentLanguage == AppLanguage.RUSSIAN
    
    var query by rememberSaveable { mutableStateOf("") }
    var dateFilter by rememberSaveable { mutableStateOf(DateFilter.ALL) }
    
    var searchResultIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var allResults by remember { mutableStateOf<List<EmailEntity>>(emptyList()) }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    
    // Режим выбора
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedIds.isNotEmpty()
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    
    LaunchedEffect(searchResultIds, activeAccount) {
        if (searchResultIds.isNotEmpty() && allResults.isEmpty() && activeAccount != null) {
            allResults = mailRepo.getEmailsByIds(searchResultIds)
        }
    }
    
    val filteredResults = remember(allResults, dateFilter) {
        allResults.filter { email ->
            dateFilter.days?.let { days ->
                val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
                email.dateReceived >= cutoff
            } ?: true
        }
    }
    
    fun search() {
        if (query.length < 2) return
        activeAccount?.let { account ->
            scope.launch {
                isSearching = true
                selectedIds = emptySet()
                val results = mailRepo.search(account.id, query)
                allResults = results
                searchResultIds = results.map { it.id }
                isSearching = false
            }
        }
    }
    
    fun deleteSelected() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                mailRepo.moveToTrash(selectedIds.toList())
            }
            when (result) {
                is EasResult.Success -> {
                    val message = if (result.data > 0) {
                        NotificationStrings.getMovedToTrash(isRussian)
                    } else {
                        NotificationStrings.getDeletedPermanently(isRussian)
                    }
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                    // Убираем удалённые из результатов
                    allResults = allResults.filter { it.id !in selectedIds }
                    searchResultIds = allResults.map { it.id }
                }
                is EasResult.Error -> {
                    android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            selectedIds = emptySet()
        }
    }
    
    fun markSelectedAsRead(read: Boolean) {
        scope.launch {
            selectedIds.forEach { id -> mailRepo.markAsRead(id, read) }
            // Обновляем локально
            allResults = allResults.map { email ->
                if (email.id in selectedIds) email.copy(read = read) else email
            }
            selectedIds = emptySet()
        }
    }
    
    fun starSelected() {
        scope.launch {
            selectedIds.forEach { id -> mailRepo.toggleFlag(id) }
            allResults = allResults.map { email ->
                if (email.id in selectedIds) email.copy(flagged = !email.flagged) else email
            }
            selectedIds = emptySet()
        }
    }
    
    // Диалог удаления
    if (showDeleteDialog) {
        com.exchange.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(if (selectedIds.size == 1) Strings.deleteEmail else Strings.deleteEmails) },
            text = { Text(Strings.emailsWillBeMovedToTrash(selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; deleteSelected() }) {
                    Text(Strings.yes, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(Strings.no) }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            if (isSelectionMode) {
                // Панель выбора
                TopAppBar(
                    title = { Text("${selectedIds.size}") },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, Strings.close)
                        }
                    },
                    actions = {
                        IconButton(onClick = { markSelectedAsRead(true) }) {
                            Icon(Icons.Default.MarkEmailRead, Strings.markRead)
                        }
                        IconButton(onClick = { starSelected() }) {
                            Icon(Icons.Default.Star, Strings.favorites)
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, Strings.delete)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            } else {
                // Обычная панель поиска с градиентом
                TopAppBar(
                    title = {
                        TextField(
                            value = query,
                            onValueChange = { 
                                query = it
                                if (it.length >= 2) search()
                            },
                            placeholder = { Text(Strings.searchInMail, color = Color.White.copy(alpha = 0.7f)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.White.copy(alpha = 0.15f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.15f),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color.White
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }),
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { 
                                        query = ""
                                        allResults = emptyList()
                                        searchResultIds = emptyList()
                                        selectedIds = emptySet()
                                    }) {
                                        Icon(Icons.Default.Clear, Strings.close, tint = Color.White)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, Strings.back, tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    modifier = Modifier.background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF7C4DFF),
                                Color(0xFF448AFF)
                            )
                        )
                    )
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Фильтры
            FilterBar(
                dateFilter = dateFilter,
                onDateFilterChange = { dateFilter = it },
                hasResults = allResults.isNotEmpty()
            )
            
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    isSearching -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    query.isEmpty() -> {
                        Text(
                            Strings.searchHint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    filteredResults.isEmpty() && query.length >= 2 -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(Strings.noResults, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (dateFilter != DateFilter.ALL) {
                                TextButton(onClick = { dateFilter = DateFilter.ALL }) {
                                    Text(Strings.resetAll)
                                }
                            }
                        }
                    }
                    allResults.isEmpty() -> {
                        Text(
                            Strings.searchHint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> {
                        val listState = rememberLazyListState()
                        
                        // Автоскролл вверх при входе в режим выделения
                        LaunchedEffect(isSelectionMode) {
                            if (isSelectionMode) {
                                listState.animateScrollToItem(0)
                            }
                        }
                        
                        LazyColumn(state = listState) {
                            // Выбрать всё (только в режиме выбора)
                            if (isSelectionMode) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedIds = if (selectedIds.size == filteredResults.size) 
                                                    emptySet() 
                                                else 
                                                    filteredResults.map { it.id }.toSet()
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = selectedIds.size == filteredResults.size,
                                            onCheckedChange = {
                                                selectedIds = if (it) filteredResults.map { e -> e.id }.toSet() else emptySet()
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(Strings.selectAll)
                                    }
                                    HorizontalDivider()
                                }
                            }
                            
                            // Количество результатов
                            item {
                                Text(
                                    text = "${if (isRussian) "Найдено" else "Found"}: ${filteredResults.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            
                            items(filteredResults, key = { it.id }) { email ->
                                SearchResultItem(
                                    email = email,
                                    query = query,
                                    isSelected = email.id in selectedIds,
                                    isSelectionMode = isSelectionMode,
                                    onClick = {
                                        if (isSelectionMode) {
                                            selectedIds = if (email.id in selectedIds) 
                                                selectedIds - email.id 
                                            else 
                                                selectedIds + email.id
                                        } else {
                                            onEmailClick(email.id)
                                        }
                                    },
                                    onLongClick = { selectedIds = selectedIds + email.id }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    dateFilter: DateFilter,
    onDateFilterChange: (DateFilter) -> Unit,
    hasResults: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DateFilter.entries.filter { it != DateFilter.ALL }.forEach { filter ->
            val label = when (filter) {
                DateFilter.TODAY -> Strings.today
                DateFilter.WEEK -> Strings.week
                DateFilter.MONTH -> Strings.month
                DateFilter.YEAR -> Strings.year
                else -> ""
            }
            FilterChip(
                selected = dateFilter == filter,
                onClick = { 
                    if (hasResults) {
                        onDateFilterChange(if (dateFilter == filter) DateFilter.ALL else filter)
                    }
                },
                label = { Text(label) },
                enabled = hasResults
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultItem(
    email: EmailEntity,
    query: String,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    
    // Цвет аватара на основе имени отправителя
    val senderName = email.fromName.ifEmpty { email.from }
    val avatarColor = getAvatarColor(senderName)
    // Используем цвет аватара для подсветки найденного текста
    val highlightColor = avatarColor
    
    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        !email.read -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surface
    }
    
    // Функция для подсветки найденного текста
    @Composable
    fun highlightText(
        text: String, 
        query: String, 
        style: androidx.compose.ui.text.TextStyle,
        fontWeight: FontWeight?,
        color: Color = MaterialTheme.colorScheme.onSurface
    ) {
        if (query.length < 2) {
            Text(
                text = text,
                style = style,
                fontWeight = fontWeight,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            val annotatedString = buildAnnotatedString {
                var currentIndex = 0
                val lowerText = text.lowercase()
                val lowerQuery = query.lowercase()
                
                while (currentIndex < text.length) {
                    val matchIndex = lowerText.indexOf(lowerQuery, currentIndex)
                    if (matchIndex == -1) {
                        // Нет больше совпадений - добавляем остаток текста
                        append(text.substring(currentIndex))
                        break
                    } else {
                        // Добавляем текст до совпадения
                        if (matchIndex > currentIndex) {
                            append(text.substring(currentIndex, matchIndex))
                        }
                        // Добавляем подсвеченное совпадение с цветом аватара
                        withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
                            append(text.substring(matchIndex, matchIndex + query.length))
                        }
                        currentIndex = matchIndex + query.length
                    }
                }
            }
            Text(
                text = annotatedString,
                style = style,
                fontWeight = fontWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick, 
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            ),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Аватар с цветом на основе имени отправителя
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else avatarColor)
                    .combinedClickable(
                        onClick = { 
                            // Клик на аватар = клик на письмо (открыть или выделить/снять в режиме выделения)
                            onClick()
                        },
                        onLongClick = {
                            // Долгий клик = выделение
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check, null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Text(
                        text = senderName.firstOrNull()?.uppercase() ?: "?",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        highlightText(
                            text = email.fromName.ifEmpty { email.from },
                            query = query,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (email.read) FontWeight.Normal else FontWeight.Bold
                        )
                    }
                    if (email.hasAttachments) {
                        Icon(
                            Icons.Default.Attachment, null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = formatSearchDate(email.dateReceived),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (email.read) MaterialTheme.colorScheme.onSurfaceVariant 
                               else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                highlightText(
                    text = email.subject,
                    query = query,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (email.read) FontWeight.Normal else FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                highlightText(
                    text = email.preview,
                    query = query,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Звёздочка
            if (email.flagged) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    Icons.Default.Star, Strings.favorites,
                    tint = Color(0xFFFFB300),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
}

private fun formatSearchDate(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = java.util.Calendar.getInstance()
    
    return when {
        diff < 24 * 60 * 60 * 1000 && 
        calendar.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) -> {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(timestamp)
        }
        diff < 7 * 24 * 60 * 60 * 1000 -> {
            java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(timestamp)
        }
        calendar.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) -> {
            java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(timestamp)
        }
        else -> {
            java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.getDefault()).format(timestamp)
        }
    }
}
