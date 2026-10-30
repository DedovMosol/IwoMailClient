package com.dedovmosol.iwomail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import com.dedovmosol.iwomail.data.repository.ContactPhotos

/**
 * Круг с буквой отправителя; включены фото из адресной книги и у [address] оно есть — фото поверх буквы (буква остаётся,
 * если фото не декодируется). Фото запрашивается, только пока круг на экране.
 */
@Composable
fun SenderAvatar(
    accountId: Long,
    address: String,
    initial: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(text = initial, fontWeight = FontWeight.Bold, color = Color.White)
        rememberContactPhoto(accountId, address)?.let { photo ->
            AsyncImage(
                model = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/** Файл фото [address] или null (фото выключены, нет фото или ещё не загружено). */
@Composable
private fun rememberContactPhoto(accountId: Long, address: String): java.io.File? {
    val context = LocalContext.current
    val photos = remember { ContactPhotos.get(context) }
    val enabled by photos.enabled.collectAsState()
    if (!enabled || accountId <= 0 || !address.contains('@')) return null
    val key = remember(accountId, address) { ContactPhotos.key(accountId, address) }
    LaunchedEffect(key) { photos.request(accountId, address) }
    // Пришло любое фото — перерисовываются только круги на экране (их десяток), без мелькания буквы до фото
    val known by photos.photos.collectAsState()
    return known[key]
}
