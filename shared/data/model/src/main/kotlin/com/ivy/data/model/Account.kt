package com.ivy.data.model

import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.IconAsset
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.sync.Identifiable
import com.ivy.data.model.sync.UniqueId
import java.util.UUID

@JvmInline
value class AccountId(override val value: UUID) : UniqueId

data class Account(
    override val id: AccountId,
    val name: NotBlankTrimmedString,
    val asset: AssetCode,
    val color: ColorInt,
    val icon: IconAsset?,
    val includeInBalance: Boolean,
    override val orderNum: Double,

    /**
     * Optional parent account. A child rolls up into its parent's total, and the parent's own balance
     * is never counted twice - see `com.ivy.data.hierarchy`. Null means "no parent" (a root account).
     *
     * Defaulted so the many existing construction sites keep compiling; a site that does not set it
     * simply produces a root account.
     */
    val parentAccountId: AccountId? = null,
) : Identifiable<AccountId>, Reorderable
