package com.ivy.shopping

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.utils.hideKeyboard
import com.ivy.navigation.screenScopedViewModel
import com.ivy.ui.R as IvyUiR
import com.ivy.wallet.ui.theme.Gradient
import com.ivy.wallet.ui.theme.GradientIvy
import com.ivy.wallet.ui.theme.White
import com.ivy.wallet.ui.theme.components.BackBottomBar
import com.ivy.wallet.ui.theme.components.CircleButton
import com.ivy.wallet.ui.theme.components.CircleButtonFilled
import com.ivy.wallet.ui.theme.components.DeleteButton
import com.ivy.wallet.ui.theme.components.IvyBorderButton
import com.ivy.wallet.ui.theme.components.IvyButton
import com.ivy.wallet.ui.theme.components.IvyCheckbox
import com.ivy.wallet.ui.theme.components.IvyIcon
import com.ivy.wallet.ui.theme.components.IvyChecklistTextField
import com.ivy.wallet.ui.theme.components.IvyDividerLine
import com.ivy.wallet.ui.theme.components.IvyNumberTextField
import com.ivy.wallet.ui.theme.components.IvyToolbar
import com.ivy.wallet.ui.theme.modal.IvyModal
import com.ivy.wallet.ui.theme.modal.ModalPositiveButton
import com.ivy.wallet.ui.theme.modal.ModalSave
import com.ivy.wallet.ui.theme.modal.ModalSet
import com.ivy.wallet.ui.theme.modal.ModalTitle
import java.util.UUID

@Composable
fun BoxWithConstraintsScope.ShoppingListScreenImpl(listId: UUID) {
    val viewModel: ShoppingListDetailViewModel = screenScopedViewModel()
    // The id is the screen's argument, so the list is loaded for it - and loaded again if the same
    // screen is ever shown for another id. Loading used to be captured once, while the screen was
    // still settling, which left the screen empty.
    LaunchedEffect(listId) {
        viewModel.load(listId)
    }
    val viewState = viewModel.uiState()
    ShoppingListScreenUi(
        viewState = viewState,
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BoxWithConstraintsScope.ShoppingListScreenUi(
    viewState: ShoppingListDetailViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // This destination is not legacy, so RootActivity passes includeSurface = false for it. Without
    // painting `pure` here the dark/AMOLED themes would render against an undefined background.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(UI.colors.pure)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            stickyHeader {
                IvyToolbar(onBack = { onEvent(ShoppingListDetailEvent.OnBack) }) {
                    Spacer(Modifier.width(16.dp))

                    Text(
                        modifier = Modifier.weight(1f),
                        text = viewState.listName,
                        style = UI.typo.b1.style(
                            color = UI.colors.pureInverse,
                            fontWeight = FontWeight.ExtraBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (viewState.exists) {
                        DeleteButton(onClick = { onEvent(ShoppingListDetailEvent.OnDeleteList) })
                    }

                    Spacer(Modifier.width(20.dp))
                }
            }

            if (!viewState.exists) {
                item {
                    MissingList()
                }
            } else {
                item {
                    Header(viewState = viewState, onEvent = onEvent)
                }

                item {
                    AddItemRow(
                        value = viewState.newItemName,
                        onValueChange = {
                            onEvent(ShoppingListDetailEvent.OnNewItemNameChange(it))
                        },
                        onAdd = { onEvent(ShoppingListDetailEvent.OnAddItem) },
                    )

                    IvyDividerLine(modifier = Modifier.padding(horizontal = 24.dp))
                }

                if (viewState.items.isEmpty()) {
                    item {
                        Text(
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                            text = stringResource(R.string.no_shopping_items),
                            style = UI.typo.nB2.style(color = UI.colors.gray),
                        )
                    }
                } else {
                    items(items = viewState.items, key = { it.id }) { row ->
                        ItemRow(
                            row = row,
                            // A template is never ticked, so it shows no checkbox at all.
                            showCheckbox = !viewState.isTemplate,
                            onToggle = { onEvent(ShoppingListDetailEvent.OnToggleItem(row.id)) },
                            onEdit = { onEvent(ShoppingListDetailEvent.OnEditItem(row.id)) },
                            onDelete = { onEvent(ShoppingListDetailEvent.OnDeleteItem(row.id)) },
                        )
                    }
                }

                item {
                    // Keeps the last row clear of the bottom bar.
                    Spacer(Modifier.height(120.dp))
                }
            }
        }

        if (viewState.exists) {
            // Checkout is offered only once something is ticked, and never for a template. It lives
            // in the bottom bar because two pill actions do not fit one toolbar.
            BackBottomBar(onBack = { onEvent(ShoppingListDetailEvent.OnBack) }) {
                IvyButton(
                    text = stringResource(R.string.checkout),
                    iconStart = IvyUiR.drawable.ic_check,
                    enabled = viewState.canCheckout,
                ) {
                    onEvent(ShoppingListDetailEvent.OnCheckoutClick)
                }
            }
        }

        viewState.checkout?.let { checkout ->
            CheckoutModal(sheet = checkout, onEvent = onEvent)
        }

        viewState.itemEditor?.let { editor ->
            ItemEditorModal(editor = editor, onEvent = onEvent)
        }
    }
}

@Composable
private fun Header(
    viewState: ShoppingListDetailViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        if (viewState.isTemplate) {
            Text(
                text = stringResource(R.string.template_label).uppercase(),
                style = UI.typo.c.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Bold
                ),
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.template_items_count, viewState.itemsTotal),
                style = UI.typo.nB2.style(color = UI.colors.gray),
            )
        } else {
            Text(
                text = stringResource(
                    R.string.items_progress,
                    viewState.itemsChecked,
                    viewState.itemsTotal,
                ),
                style = UI.typo.nB2.style(color = UI.colors.gray),
            )

            Spacer(modifier = Modifier.height(8.dp))

            ShoppingProgressBar(progress = viewState.progress)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // The amount is already formatted by the view model and arrives inside this single string, so
        // it carries the `AmountCurrencyB1` treatment (20sp, bold, pureInverse) rather than being
        // passed to `AmountCurrencyB1`, which takes a Double and would re-format the money.
        Text(
            text = stringResource(
                R.string.estimated_left,
                viewState.estimatedRemainingText,
            ),
            style = UI.typo.b1.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.ExtraBold
            ),
        )

        PostedToLedger(viewState = viewState, onEvent = onEvent)
    }
}

/**
 * Where the trip went.
 *
 * It is a real action only when there is exactly one transaction and it still exists. A per-item trip
 * just says how many ledger rows it became, and a deleted transaction says so - neither is a dead
 * entry point.
 */
@Composable
private fun PostedToLedger(
    viewState: ShoppingListDetailViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
) {
    when {
        viewState.postedTransactionId != null -> {
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .clip(UI.shapes.rFull)
                    .clickable { onEvent(ShoppingListDetailEvent.OnOpenPostedTransaction) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IvyIcon(
                    icon = IvyUiR.drawable.ic_arrow_right,
                    tint = UI.colors.primary,
                    contentDescription = stringResource(R.string.posted_to_ledger),
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    text = stringResource(R.string.posted_to_ledger),
                    style = UI.typo.nB2.style(
                        color = UI.colors.primary,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }

        viewState.postedEntryCount > 1 -> {
            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(
                    R.string.posted_to_ledger_multiple,
                    viewState.postedEntryCount,
                ),
                style = UI.typo.nC.style(color = UI.colors.gray),
            )
        }

        viewState.postedTransactionDeleted -> {
            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.posted_to_ledger_deleted),
                style = UI.typo.nC.style(color = UI.colors.gray),
            )
        }
    }
}

@Composable
private fun AddItemRow(
    value: String,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    var itemValue by remember {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }

    // The view model clears the name after a successful add, so the field has to follow it.
    LaunchedEffect(value) {
        if (value != itemValue.text) {
            itemValue = TextFieldValue(value, TextRange(value.length))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IvyChecklistTextField(
            modifier = Modifier.weight(1f),
            value = itemValue,
            hint = stringResource(R.string.item_name),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onAdd() }
            ),
        ) { newValue ->
            itemValue = newValue
            onValueChange(newValue.text)
        }

        CircleButtonFilled(
            icon = IvyUiR.drawable.ic_plus,
            contentDescription = stringResource(R.string.add_item),
        ) {
            onAdd()
        }
    }
}

@Composable
private fun ItemRow(
    row: ShoppingItemRowViewState,
    showCheckbox: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = showCheckbox, onClick = onToggle)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCheckbox) {
            IvyCheckbox(
                checked = row.checked,
                onCheckedChange = { onToggle() },
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = row.name,
                style = UI.typo.b2.style(
                    color = if (row.checked) UI.colors.gray else UI.colors.pureInverse,
                    fontWeight = FontWeight.Medium
                ),
                textDecoration = if (row.checked) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val detail = row.detailText
            if (detail != null) {
                Text(
                    text = detail,
                    style = UI.typo.nC.style(color = UI.colors.gray),
                )
            }
        }

        CircleButton(
            icon = IvyUiR.drawable.ic_edit,
            contentDescription = stringResource(R.string.edit_item),
            onClick = onEdit,
        )

        DeleteButton(onClick = onDelete)
    }
}

/**
 * The explicit editor for one item's price and quantity.
 *
 * It is reached from the item's own row and works the same in templates and trips. It is never
 * opened by ticking an item: ticking stays silent.
 */
@Composable
private fun BoxScope.ItemEditorModal(
    editor: ShoppingItemEditorViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
) {
    // Stable id: a fresh UUID per recomposition would leak modal back handlers.
    val modalId = remember { UUID.randomUUID() }

    var priceValue by remember(editor.itemId) {
        mutableStateOf(TextFieldValue(editor.priceText, TextRange(editor.priceText.length)))
    }
    var quantityValue by remember(editor.itemId) {
        mutableStateOf(TextFieldValue(editor.quantityText, TextRange(editor.quantityText.length)))
    }

    val focusManager = LocalFocusManager.current
    val view = LocalView.current

    IvyModal(
        id = modalId,
        visible = true,
        dismiss = { onEvent(ShoppingListDetailEvent.OnEditorDismiss) },
        PrimaryAction = {
            ModalSave(enabled = editor.canSave) {
                onEvent(ShoppingListDetailEvent.OnEditorSave)
            }
        },
    ) {
        Spacer(Modifier.height(32.dp))

        ModalTitle(text = editor.title)

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            // Precedent: `IntervalPickerRow`. The gradient pill is the design system's numeric input.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    modifier = Modifier.padding(start = 12.dp),
                    text = stringResource(R.string.item_price).uppercase(),
                    style = UI.typo.c.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.Bold
                    ),
                )

                Spacer(Modifier.height(6.dp))

                IvyNumberTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = GradientIvy.asHorizontalBrush(),
                            shape = UI.shapes.rFull
                        )
                        .padding(vertical = 12.dp),
                    value = priceValue,
                    hint = null,
                    textColor = White,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                ) { newValue ->
                    priceValue = newValue
                    onEvent(ShoppingListDetailEvent.OnEditorPriceChange(newValue.text))
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    modifier = Modifier.padding(start = 12.dp),
                    text = stringResource(R.string.item_quantity).uppercase(),
                    style = UI.typo.c.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.Bold
                    ),
                )

                Spacer(Modifier.height(6.dp))

                IvyNumberTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = GradientIvy.asHorizontalBrush(),
                            shape = UI.shapes.rFull
                        )
                        .padding(vertical = 12.dp),
                    value = quantityValue,
                    hint = null,
                    textColor = White,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { hideKeyboard(view) }
                    ),
                ) { newValue ->
                    quantityValue = newValue
                    onEvent(ShoppingListDetailEvent.OnEditorQuantityChange(newValue.text))
                }
            }
        }

        val historyPrice = editor.historyPriceText
        if (historyPrice != null) {
            Spacer(Modifier.height(12.dp))

            val historyCurrency = editor.historyCurrency.orEmpty()
            val historyDate = editor.historyDateText.orEmpty()
            Text(
                modifier = Modifier.padding(horizontal = 32.dp),
                text = if (editor.historyTimes == 1) {
                    stringResource(R.string.price_history_on_once, historyPrice, historyCurrency, historyDate)
                } else {
                    stringResource(
                        R.string.price_history_on_many,
                        historyPrice,
                        historyCurrency,
                        historyDate,
                        editor.historyTimes,
                    )
                },
                style = UI.typo.nC.style(color = UI.colors.primary),
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = stringResource(R.string.item_editor_no_price_hint),
            style = UI.typo.nC.style(color = UI.colors.gray),
        )

        Spacer(Modifier.height(48.dp))
    }
}

/**
 * One review of everything that is ticked: a box per item with its price, and a choice of how the
 * trip reaches the ledger - one transaction for the whole trip (default) or one row per item.
 *
 * `IvyModal` already applies `verticalScroll`, so the body is a plain `Column`. A `LazyColumn` here
 * would be measured with an infinite height and crash.
 */
@Composable
private fun BoxScope.CheckoutModal(
    sheet: ShoppingCheckoutViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
) {
    // Stable id: a fresh UUID per recomposition would leak modal back handlers.
    val modalId = remember { UUID.randomUUID() }
    val picker = sheet.picker
    // One scroll state per content state, so the picker and the review do not share a position.
    val reviewScrollState = rememberScrollState()
    val pickerScrollState = rememberScrollState()

    IvyModal(
        id = modalId,
        visible = true,
        dismiss = {
            if (picker != null) {
                onEvent(ShoppingListDetailEvent.OnCheckoutPickerDismiss)
            } else {
                onEvent(ShoppingListDetailEvent.OnCheckoutDismiss)
            }
        },
        scrollState = if (picker != null) pickerScrollState else reviewScrollState,
        PrimaryAction = {
            if (picker != null) {
                ModalSet(label = stringResource(R.string.cancel)) {
                    onEvent(ShoppingListDetailEvent.OnCheckoutPickerDismiss)
                }
            } else {
                ModalPositiveButton(
                    text = stringResource(R.string.post_to_ledger),
                    iconStart = IvyUiR.drawable.ic_check,
                    enabled = sheet.canConfirm && !sheet.posting,
                ) {
                    onEvent(ShoppingListDetailEvent.OnCheckoutConfirm)
                }
            }
        },
    ) {
        // The account/category picker is the same modal's second content state, never a second modal.
        if (picker != null) {
            CheckoutPicker(picker = picker, onEvent = onEvent)
        } else {
            CheckoutReview(sheet = sheet, onEvent = onEvent)
        }
    }
}

@Composable
private fun CheckoutReview(
    sheet: ShoppingCheckoutViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
) {
    Column {
        Spacer(Modifier.height(32.dp))

        ModalTitle(text = stringResource(R.string.checkout))

        Spacer(Modifier.height(16.dp))

        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = stringResource(R.string.checkout_mode_hint).uppercase(),
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.Bold
            ),
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            CheckoutModeButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.checkout_mode_single),
                selected = sheet.mode == ShoppingCheckoutMode.SingleTransaction,
            ) {
                onEvent(
                    ShoppingListDetailEvent.OnCheckoutModeChange(
                        ShoppingCheckoutMode.SingleTransaction
                    )
                )
            }

            Spacer(Modifier.width(12.dp))

            CheckoutModeButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.checkout_mode_rows),
                selected = sheet.mode == ShoppingCheckoutMode.RowPerItem,
            ) {
                onEvent(
                    ShoppingListDetailEvent.OnCheckoutModeChange(
                        ShoppingCheckoutMode.RowPerItem
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = if (sheet.mode == ShoppingCheckoutMode.SingleTransaction) {
                stringResource(R.string.checkout_summary_single, sheet.postableCount)
            } else {
                stringResource(R.string.checkout_summary, sheet.postableCount, sheet.rows.size)
            },
            style = UI.typo.nC.style(color = UI.colors.gray),
        )

        if (sheet.mode == ShoppingCheckoutMode.SingleTransaction) {
            SingleTransactionTarget(sheet = sheet, onEvent = onEvent)
        }

        Spacer(Modifier.height(12.dp))

        IvyDividerLine(modifier = Modifier.padding(horizontal = 24.dp))

        // Plain iteration: a nested lazy list would get an infinite height inside `IvyModal`.
        sheet.rows.forEach { row ->
            CheckoutRow(
                row = row,
                // The per-row account only matters when every item is its own row.
                showRowTargets = sheet.mode == ShoppingCheckoutMode.RowPerItem,
                onEvent = onEvent,
            )

            IvyDividerLine(modifier = Modifier.padding(horizontal = 24.dp))
        }

        if (sheet.refusedEmptyTotal) {
            Text(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                text = stringResource(R.string.checkout_nothing_priced),
                style = UI.typo.nB2.style(color = UI.colors.red),
            )
        }

        Spacer(Modifier.height(16.dp))

        Column(modifier = Modifier.padding(horizontal = 32.dp)) {
            Text(
                text = stringResource(R.string.checkout_total).uppercase(),
                style = UI.typo.c.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Bold
                ),
            )

            Spacer(Modifier.height(4.dp))

            // Already formatted by the view model, so the `AmountCurrencyB1` treatment is applied to
            // the formatted text instead of re-formatting a Double here.
            Text(
                text = sheet.totalText,
                style = UI.typo.b1.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold
                ),
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun CheckoutModeButton(
    modifier: Modifier = Modifier,
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // The selected mode is the filled pill, the other one is outlined. `IvyOutlinedButton` is not
    // used here because it has no `enabled` parameter.
    if (selected) {
        IvyButton(
            modifier = modifier,
            text = text,
            backgroundGradient = GradientIvy,
            wrapContentMode = false,
            onClick = onClick,
        )
    } else {
        IvyBorderButton(
            modifier = modifier,
            text = text,
            backgroundGradient = GradientIvy,
            wrapContentMode = false,
            onClick = onClick,
        )
    }
}

/** One account and one category for the whole trip - the price of posting a single row. */
@Composable
private fun SingleTransactionTarget(
    sheet: ShoppingCheckoutViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
) {
    Text(
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 8.dp),
        text = stringResource(R.string.checkout_single_hint),
        style = UI.typo.nC.style(color = UI.colors.gray),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IvyBorderButton(
            modifier = Modifier.weight(1f),
            text = sheet.tripAccountLabel ?: stringResource(R.string.checkout_choose_account),
            wrapContentMode = false,
        ) {
            onEvent(ShoppingListDetailEvent.OnCheckoutAccountClick(null))
        }

        Spacer(Modifier.width(12.dp))

        IvyBorderButton(
            modifier = Modifier.weight(1f),
            text = sheet.tripCategoryLabel ?: stringResource(R.string.checkout_no_category),
            wrapContentMode = false,
        ) {
            onEvent(ShoppingListDetailEvent.OnCheckoutCategoryClick(null))
        }
    }
}

@Composable
private fun CheckoutRow(
    row: ShoppingCheckoutRowViewState,
    showRowTargets: Boolean,
    onEvent: (ShoppingListDetailEvent) -> Unit,
) {
    var priceValue by remember(row.itemId) {
        mutableStateOf(TextFieldValue(row.priceText, TextRange(row.priceText.length)))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = row.name,
                style = UI.typo.b2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = row.quantityText,
                style = UI.typo.nC.style(color = UI.colors.gray),
            )
        }

        // The history hint sits next to the price it describes, but it never fills it in.
        val historyPrice = row.historyPriceText
        if (historyPrice != null) {
            Spacer(Modifier.height(4.dp))

            val historyCurrency = row.historyCurrency.orEmpty()
            Text(
                text = if (row.historyTimes == 1) {
                    stringResource(R.string.price_history_once, historyPrice, historyCurrency)
                } else {
                    stringResource(
                        R.string.price_history_many,
                        historyPrice,
                        historyCurrency,
                        row.historyTimes,
                    )
                },
                style = UI.typo.nC.style(color = UI.colors.gray),
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    modifier = Modifier.padding(start = 12.dp),
                    text = stringResource(R.string.checkout_price).uppercase(),
                    style = UI.typo.c.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.Bold
                    ),
                )

                Spacer(Modifier.height(6.dp))

                // Gradient when the row has a usable price, neutral when it will be skipped.
                IvyNumberTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = if (row.skipped) {
                                Gradient.solid(UI.colors.medium).asHorizontalBrush()
                            } else {
                                GradientIvy.asHorizontalBrush()
                            },
                            shape = UI.shapes.rFull
                        )
                        .padding(vertical = 10.dp),
                    value = priceValue,
                    hint = null,
                    textColor = if (row.skipped) UI.colors.pureInverse else White,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                ) { newValue ->
                    priceValue = newValue
                    onEvent(
                        ShoppingListDetailEvent.OnCheckoutPriceChange(row.itemId, newValue.text)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Text(
                text = if (row.skipped) {
                    stringResource(R.string.checkout_skipped)
                } else {
                    row.lineTotalText
                },
                style = UI.typo.nB2.style(
                    color = if (row.skipped) UI.colors.red else UI.colors.pureInverse
                ),
            )
        }

        if (showRowTargets) {
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                IvyBorderButton(
                    modifier = Modifier.weight(1f),
                    text = if (row.hasAccount) {
                        row.accountLabel
                    } else {
                        stringResource(R.string.checkout_choose_account)
                    },
                    wrapContentMode = false,
                ) {
                    onEvent(ShoppingListDetailEvent.OnCheckoutAccountClick(row.itemId))
                }

                Spacer(Modifier.width(12.dp))

                IvyBorderButton(
                    modifier = Modifier.weight(1f),
                    text = row.categoryLabel ?: stringResource(R.string.checkout_no_category),
                    wrapContentMode = false,
                ) {
                    onEvent(ShoppingListDetailEvent.OnCheckoutCategoryClick(row.itemId))
                }
            }
        }
    }
}

/**
 * The second content state of the checkout modal. Options come pre-filtered from the view model,
 * and "no category" is always one of them.
 */
@Composable
private fun CheckoutPicker(
    picker: ShoppingPickerViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
) {
    Column {
        Spacer(Modifier.height(32.dp))

        ModalTitle(
            text = when (picker.kind) {
                ShoppingPickKind.Account -> stringResource(R.string.checkout_account)
                ShoppingPickKind.Category -> stringResource(R.string.checkout_category)
            }
        )

        Spacer(Modifier.height(16.dp))

        if (picker.options.isEmpty()) {
            Text(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                text = stringResource(R.string.checkout_no_options),
                style = UI.typo.nB2.style(color = UI.colors.gray),
            )
        }

        picker.options.forEach { option ->
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onEvent(
                            ShoppingListDetailEvent.OnCheckoutPick(
                                itemId = picker.itemId,
                                optionId = option.id,
                            )
                        )
                    }
                    .padding(horizontal = 32.dp, vertical = 12.dp),
                text = if (option.id == null) {
                    stringResource(R.string.checkout_no_category)
                } else {
                    option.label
                },
                style = UI.typo.b1.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.Normal
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun MissingList() {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        text = stringResource(R.string.shopping_list_gone),
        style = UI.typo.b2.style(
            color = UI.colors.gray,
            fontWeight = FontWeight.Medium
        ),
        textAlign = TextAlign.Center,
    )
}
