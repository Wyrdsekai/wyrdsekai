package org.wyrdsekai.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.wyrdsekai.app.i18n.I18nStrings
import org.wyrdsekai.app.protocol.Hint

@Composable
fun HintChips(
    hints: List<Hint>,
    onSelect: (Int) -> Unit,
    locale: String = "en",
    modifier: Modifier = Modifier,
) {
    if (hints.isEmpty()) return

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(hints) { index, hint ->
            AssistChip(
                onClick = { onSelect(index) },
                label = { Text(I18nStrings.resolve(hint.labelKey, hint.label, locale)) },
            )
        }
    }
}
