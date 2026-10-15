package com.ivy.shopping

import com.ivy.data.db.entity.ShoppingItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ShoppingListRulesTest {

    private val listId = UUID.randomUUID()
    private val accountId = UUID.randomUUID()
    private val categoryId = UUID.randomUUID()

    private fun item(
        name: String = "Milk",
        quantity: Double = 1.0,
        unitPrice: Double? = null,
        isChecked: Boolean = false,
        categoryId: UUID? = null,
        convertedTransactionId: UUID? = null,
    ) = ShoppingItemEntity(
        listId = listId,
        name = name,
        quantity = quantity,
        unitPrice = unitPrice,
        isChecked = isChecked,
        categoryId = categoryId,
        convertedTransactionId = convertedTransactionId,
    )

    @Test
    fun `an unticked item is not postable`() {
        assertFalse(item(isChecked = false).isPostable())
    }

    @Test
    fun `a ticked item with no transaction yet is postable`() {
        assertTrue(item(isChecked = true).isPostable())
    }

    @Test
    fun `an already posted item is never postable again`() {
        val item = item(isChecked = true, convertedTransactionId = UUID.randomUUID())

        assertFalse(item.isPostable())
    }

    @Test
    fun `a missing price counts as zero rather than being guessed`() {
        assertEquals(0.0, item(quantity = 3.0, unitPrice = null).lineTotal(), 0.0001)
    }

    @Test
    fun `line total multiplies price by quantity`() {
        assertEquals(7.50, item(quantity = 3.0, unitPrice = 2.50).lineTotal(), 0.0001)
    }

    @Test
    fun `the remaining estimate only counts what is still to buy`() {
        val items = listOf(
            item(name = "Milk", unitPrice = 5.0, isChecked = true),
            item(name = "Bread", unitPrice = 3.0, isChecked = false),
            item(name = "Eggs", unitPrice = 2.0, isChecked = false),
        )

        assertEquals(5.0, items.remainingEstimate(), 0.0001)
    }

    @Test
    fun `checked count reports what is in the basket`() {
        val items = listOf(
            item(isChecked = true),
            item(isChecked = true),
            item(isChecked = false),
        )

        assertEquals(2, items.checkedCount())
    }

    @Test
    fun `checkout total excludes items that were already posted`() {
        val items = listOf(
            item(name = "Milk", unitPrice = 5.0, isChecked = true),
            item(name = "Bread", unitPrice = 3.0, isChecked = true),
            item(
                name = "Already done",
                unitPrice = 100.0,
                isChecked = true,
                convertedTransactionId = UUID.randomUUID(),
            ),
        )

        assertEquals(8.0, items.checkoutTotal(), 0.0001)
    }

    @Test
    fun `awaiting price lists only ticked unpriced items`() {
        val items = listOf(
            item(name = "Milk", unitPrice = null, isChecked = true),
            item(name = "Bread", unitPrice = 3.0, isChecked = true),
            item(name = "Not ticked", unitPrice = null, isChecked = false),
        )

        assertEquals(listOf("Milk"), items.awaitingPrice().map { it.name })
    }

    @Test
    fun `checkout builds one ledger line per ticked item`() {
        val items = listOf(
            item(name = "Milk", quantity = 2.0, unitPrice = 4.0, isChecked = true),
            item(name = "Bread", unitPrice = 3.0, isChecked = true),
        )

        val lines = buildCheckoutLines(items, prices = emptyMap(), accountId = accountId, categoryId = null)

        assertEquals(2, lines.size)
        assertEquals(listOf("Milk", "Bread"), lines.map { it.title })
        assertEquals(8.0, lines[0].amount, 0.0001)
        assertEquals(3.0, lines[1].amount, 0.0001)
    }

    @Test
    fun `a price entered at the till beats the price stored on the item`() {
        val item = item(name = "Milk", unitPrice = 4.0, isChecked = true)

        val lines = buildCheckoutLines(
            items = listOf(item),
            prices = mapOf(item.id to 6.0),
            accountId = accountId,
            categoryId = null,
        )

        assertEquals(6.0, lines.single().amount, 0.0001)
    }

    @Test
    fun `an unpriced item is skipped instead of posting a zero expense`() {
        val items = listOf(item(name = "Milk", unitPrice = null, isChecked = true))

        assertTrue(
            buildCheckoutLines(items, prices = emptyMap(), accountId = accountId, categoryId = null)
                .isEmpty()
        )
    }

    @Test
    fun `unticked items never reach the ledger`() {
        val items = listOf(item(name = "Milk", unitPrice = 4.0, isChecked = false))

        assertTrue(
            buildCheckoutLines(items, prices = emptyMap(), accountId = accountId, categoryId = null)
                .isEmpty()
        )
    }

    @Test
    fun `the item category wins over the list default`() {
        val items = listOf(
            item(name = "Milk", unitPrice = 1.0, isChecked = true, categoryId = categoryId),
            item(name = "Bread", unitPrice = 1.0, isChecked = true, categoryId = null),
        )

        val lines = buildCheckoutLines(items, prices = emptyMap(), accountId = accountId, categoryId = null)

        assertEquals(categoryId, lines.first { it.title == "Milk" }.categoryId)
        assertEquals(null, lines.first { it.title == "Bread" }.categoryId)
    }

    @Test
    fun `the priced line is remembered back onto the item`() {
        assertEquals(2.5, unitPriceFromLine(amount = 5.0, quantity = 2.0), 0.0001)
    }

    @Test
    fun `a zero quantity does not divide by zero`() {
        assertEquals(5.0, unitPriceFromLine(amount = 5.0, quantity = 0.0), 0.0001)
    }
}
