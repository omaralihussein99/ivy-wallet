package com.ivy.shopping

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.navigation.screenScopedViewModel
import com.ivy.ui.R as IvyUiR
import com.ivy.ui.rememberScrollPositionListState
import com.ivy.wallet.ui.theme.components.BackBottomBar
import com.ivy.wallet.ui.theme.components.CircleButtonFilled
import com.ivy.wallet.ui.theme.components.DeleteButton
import com.ivy.wallet.ui.theme.components.IvyButton
import com.ivy.wallet.ui.theme.components.IvyNameTextField
import com.ivy.wallet.ui.theme.components.IvySwitch
import com.ivy.wallet.ui.theme.modal.IvyModal
import com.ivy.wallet.ui.theme.modal.ModalAdd
import com.ivy.wallet.ui.theme.modal.ModalTitle
import java.util.UUID

@Composable
fun BoxWithConstraintsScope.ShoppingListsScreenImpl() {
    val viewModel: ShoppingListsViewModel = screenScopedViewModel()
    val viewState = viewModel.uiState()
    ShoppingListsScreenUi(
        viewState = viewState,
        onEvent = viewModel::onEvent,
    )
}

@Composable
fun BoxWithConstraintsScope.ShoppingListsScreenUi(
    viewState: ShoppingListsViewState,
    onEvent: (ShoppingListsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // This destination is not legacy, so RootActivity passes includeSurface = false for it. Without
    // painting `pure` here the dark/AMOLED themes would render against an undefined background.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(UI.colors.pure)
    ) {
        val listState = rememberScrollPositionListState(key = "shopping_lists_lazy_column")

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            state = listState,
        ) {
            item {
                Spacer(Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(24.dp))

                    Text(
                        text = stringResource(R.string.shopping_lists),
                        style = UI.typo.h2.style(
                            color = UI.colors.pureInverse,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                }
            }

            if (viewState.templates.isEmpty() && viewState.trips.isEmpty()) {
                item {
                    EmptyState()
                }
            } else {
                if (viewState.templates.isNotEmpty()) {
                    item(key = "templates_header") {
                        SectionHeader(text = stringResource(R.string.section_templates))
                    }
                    items(items = viewState.templates, key = { it.id }) { row ->
                        ShoppingListCard(
                            row = row,
                            onOpen = { onEvent(ShoppingListsEvent.OnOpenList(row.id)) },
                            onDelete = { onEvent(ShoppingListsEvent.OnDeleteList(row.id)) },
                            onNewTrip = { onEvent(ShoppingListsEvent.OnNewTripFromTemplate(row.id)) },
                        )

                        Spacer(Modifier.height(16.dp))
                    }
                }

                if (viewState.trips.isNotEmpty()) {
                    item(key = "trips_header") {
                        SectionHeader(text = stringResource(R.string.section_trips))
                    }
                    items(items = viewState.trips, key = { it.id }) { row ->
                        ShoppingListCard(
                            row = row,
                            onOpen = { onEvent(ShoppingListsEvent.OnOpenList(row.id)) },
                            onDelete = { onEvent(ShoppingListsEvent.OnDeleteList(row.id)) },
                            // A trip is already a copy of its template, so it has nothing to start.
                            onNewTrip = null,
                        )

                        Spacer(Modifier.height(16.dp))
                    }
                }
            }

            item {
                // Keeps the last card clear of the bottom bar.
                Spacer(Modifier.height(150.dp))
            }
        }

        BackBottomBar(onBack = { onEvent(ShoppingListsEvent.OnBack) }) {
            IvyButton(
                text = stringResource(R.string.new_shopping_list),
                iconStart = IvyUiR.drawable.ic_plus,
            ) {
                onEvent(ShoppingListsEvent.OnAddListClick)
            }
        }

        if (viewState.addListDialogVisible) {
            NewListModal(
                name = viewState.newListName,
                isTemplate = viewState.newListIsTemplate,
                onNameChange = { onEvent(ShoppingListsEvent.OnNewListNameChange(it)) },
                onIsTemplateChange = { onEvent(ShoppingListsEvent.OnNewListIsTemplateChange(it)) },
                onConfirm = { onEvent(ShoppingListsEvent.OnCreateList) },
                onDismiss = { onEvent(ShoppingListsEvent.OnDismissAddList) },
            )
        }
    }
}

/**
 * `SettingsScreen`'s section divider: 32dp of air, then the label at 32dp from the start. The
 * trailing 16dp is what the settings items add themselves before their first card.
 */
@Composable
private fun SectionHeader(text: String) {
    Column {
        Spacer(Modifier.height(32.dp))

        Text(
            modifier = Modifier.padding(start = 32.dp),
            text = text,
            style = UI.typo.b2.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.Bold
            )
        )

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ShoppingListCard(
    row: ShoppingListRowViewState,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onNewTrip: (() -> Unit)?,
) {
    // Settings-style card: 16dp side gutters, `r4` corners and a 2dp medium border.
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .border(2.dp, UI.colors.medium, UI.shapes.r4)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row's own click area stops before the buttons on purpose: the "start a trip" button used
        // to sit inside the row's clickable region, so one tap could open the template *and* start a
        // trip, and the two navigations raced each other.
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = row.name,
                style = UI.typo.b1.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = row.subtitle(),
                style = UI.typo.nB2.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Normal
                ).copy(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (onNewTrip != null) {
            CircleButtonFilled(
                icon = IvyUiR.drawable.ic_arrow_right,
                contentDescription = stringResource(R.string.new_trip_from_template),
            ) {
                onNewTrip()
            }
        }

        DeleteButton(onClick = onDelete)
    }
}

@Composable
private fun ShoppingListRowViewState.subtitle(): String {
    val progress = stringResource(R.string.items_progress, itemsChecked, itemsTotal)
    return if (itemsTotal == 0) {
        progress
    } else {
        "$progress  ·  $estimatedRemainingText"
    }
}

@Composable
private fun EmptyState() {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 48.dp),
        text = stringResource(R.string.no_shopping_lists),
        style = UI.typo.b2.style(
            color = UI.colors.gray,
            fontWeight = FontWeight.Medium
        ),
        textAlign = TextAlign.Center,
    )
}

/**
 * The "new list" modal. Same shape as the other Ivy modals: a title, one name field with the
 * component's own underline, and the actions row owning Add.
 */
@Composable
private fun BoxScope.NewListModal(
    name: String,
    isTemplate: Boolean,
    onNameChange: (String) -> Unit,
    onIsTemplateChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Stable id: a fresh UUID per recomposition would leak modal back handlers.
    val modalId = remember { UUID.randomUUID() }
    var nameValue by remember { mutableStateOf(TextFieldValue(name)) }

    IvyModal(
        id = modalId,
        visible = true,
        dismiss = onDismiss,
        PrimaryAction = {
            ModalAdd {
                onConfirm()
            }
        },
    ) {
        Spacer(Modifier.height(32.dp))

        ModalTitle(text = stringResource(R.string.new_shopping_list))

        Spacer(Modifier.height(16.dp))

        IvyNameTextField(
            modifier = Modifier.padding(start = 28.dp, end = 36.dp),
            underlineModifier = Modifier.padding(start = 24.dp, end = 32.dp),
            value = nameValue,
            hint = stringResource(R.string.shopping_list_name),
        ) { newValue ->
            nameValue = newValue
            onNameChange(newValue.text)
        }

        Spacer(Modifier.height(24.dp))

        // Settings-style row: the label and its hint on the left, the switch on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.save_as_template),
                    style = UI.typo.b2.style(
                        color = UI.colors.pureInverse,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.save_as_template_hint),
                    style = UI.typo.nC.style(color = UI.colors.gray),
                )
            }

            Spacer(Modifier.width(16.dp))

            IvySwitch(
                enabled = isTemplate,
                onEnabledChange = onIsTemplateChange,
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}

/**
 * Thin rounded progress track used by the list header. Hand rolled so the appearance is
 * identical across Compose versions instead of depending on the platform indicator. It uses the
 * neutral track and the primary fill of the design system, not a threshold colour.
 */
@Composable
internal fun ShoppingProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(UI.shapes.rFull)
            .background(UI.colors.medium)
    ) {
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(UI.shapes.rFull)
                    .background(UI.colors.primary)
            )
        }
    }
}
