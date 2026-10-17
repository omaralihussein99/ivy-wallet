package com.ivy.transaction

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.ui.R
import kotlin.math.round

/**
 * The items a shopping trip was made of, shown inside the transaction the trip produced.
 *
 * Ivy has no multi-line transaction, so a trip posted as "one transaction" keeps its breakdown in the
 * shopping items themselves: every item of the trip points at that transaction through
 * `shopping_items.convertedTransactionId`. This section is the read side of that link - it appears
 * only for transactions that have shopping items pointing at them, i.e. only for transactions a
 * shopping trip produced.
 *
 * It is drawn with this screen's own design system on purpose: the transaction screen is an old,
 * un-restyled Ivy screen, and a Material3 card dropped into it would look foreign.
 */
data class ShoppingBreakdownRow(
    val itemId: String,
    val name: String,
    val quantityText: String,
    val lineTotalText: String,
    val categoryLabel: String?,
)

data class ShoppingBreakdownViewState(
    val rows: List<ShoppingBreakdownRow>,
    val totalText: String,
)

@Composable
internal fun ShoppingItemsBreakdown(
    state: ShoppingBreakdownViewState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            modifier = Modifier.padding(horizontal = 24.dp),
            text = stringResource(R.string.shopping_items_breakdown),
            style = UI.typo.nB2.style(
                color = UI.colors.mediumInverse,
                fontWeight = FontWeight.SemiBold,
            ),
        )

        Spacer(Modifier.height(8.dp))

        state.rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = UI.typo.b2.style(textAlign = TextAlign.Left),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val category = row.categoryLabel
                    if (category != null) {
                        Text(
                            text = category,
                            style = UI.typo.nB2.style(
                                textAlign = TextAlign.Left,
                                color = UI.colors.mediumInverse,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Text(
                    text = row.quantityText,
                    style = UI.typo.nB2.style(
                        textAlign = TextAlign.End,
                        color = UI.colors.mediumInverse,
                    ),
                )

                Spacer(Modifier.width(12.dp))

                Text(
                    text = row.lineTotalText,
                    style = UI.typo.b2.style(textAlign = TextAlign.End),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.shopping_items_total),
                style = UI.typo.nB2.style(
                    textAlign = TextAlign.Left,
                    color = UI.colors.mediumInverse,
                ),
            )
            Text(
                text = state.totalText,
                style = UI.typo.b2.style(textAlign = TextAlign.End),
            )
        }
    }
}

/**
 * A quantity as a plain count of things - "2", "1.5" - with no currency and no decimals when it is a
 * whole number. This mirrors how the shopping screens render a quantity; it is duplicated here
 * because the shopping feature is a different module and a quantity is not money, so it must not go
 * through the money formatter.
 */
internal fun formatQuantityText(value: Double): String {
    if (value % 1.0 == 0.0) return value.toInt().toString()

    val hundredths = round(value * 100.0).toLong()
    val whole = hundredths / 100
    val fraction = (hundredths % 100).toInt()
    return when {
        fraction == 0 -> whole.toString()
        fraction % 10 == 0 -> "$whole.${fraction / 10}"
        fraction < 10 -> "$whole.0$fraction"
        else -> "$whole.$fraction"
    }
}
