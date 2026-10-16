package com.ivy.wallet.domain.deprecated.logic.model

import androidx.compose.ui.graphics.Color
import java.util.UUID

data class CreateAccountData(
    val name: String,
    val currency: String,
    val color: Color,
    val icon: String?,
    val balance: Double,
    val includeBalance: Boolean = true,
    /** Parent account for the hierarchy. Null means "no parent" (a root account). */
    val parentAccountId: UUID? = null,
)
