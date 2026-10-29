package com.dedovmosol.iwomail.ui.screens.emaillist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dedovmosol.iwomail.ui.isRussian

private val THUMBNAIL_SIZE = 36.dp

/**
 * Миниатюра картинки письма в строке списка. Картинок больше одной — в углу «+N» (сколько ещё), как в Gmail и Outlook:
 * одна миниатюра на строку держит высоту строки и прокрутку лёгкой. Декодируется в размер миниатюры, не целиком.
 */
@Composable
internal fun ListThumbnail(preview: ListPreview, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { THUMBNAIL_SIZE.roundToPx() }
    val model = remember(preview.path, sizePx) {
        ImageRequest.Builder(context).data(java.io.File(preview.path)).crossfade(true).size(sizePx).build()
    }
    val more = preview.moreCount
    val description = thumbnailDescription(preview.imageCount, isRussian())
    Box(
        modifier = modifier
            .size(THUMBNAIL_SIZE)
            .clip(RoundedCornerShape(4.dp))
            .semantics { contentDescription = description }
    ) {
        AsyncImage(model = model, contentDescription = null, modifier = Modifier.size(THUMBNAIL_SIZE), contentScale = ContentScale.Crop)
        if (more > 0) {
            Text(
                text = moreBadgeText(more),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(topStart = 4.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
    }
}

/** «+N» на миниатюре; больше 99 — «+99». */
internal fun moreBadgeText(more: Int): String = "+${more.coerceAtMost(99)}"

/** Для TalkBack: «3 изображения» / «3 images». */
internal fun thumbnailDescription(count: Int, russian: Boolean): String =
    if (!russian) {
        if (count == 1) "1 image" else "$count images"
    } else {
        val word = when {
            count % 100 in 11..14 -> "изображений"
            count % 10 == 1 -> "изображение"
            count % 10 in 2..4 -> "изображения"
            else -> "изображений"
        }
        "$count $word"
    }
