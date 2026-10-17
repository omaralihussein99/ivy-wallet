package com.ivy.shopping

import com.ivy.data.db.entity.ShoppingItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * The one shape the app posts, driven the way the checkout sheet drives it: ticked items become
     * drafts, every box is pre-filled from the price stored on the item, and what the user typed into
     * the box is what finally posts.
     *
     * [boxPrices] replaces what the user typed; when it is null every box keeps the price it arrived
     * pre-filled with, exactly as the screen does it.
     */
    private fun draftLines(
        items: List<ShoppingItemEntity>,
        tripName: String = "Weekly groceries",
        boxPrices: Map<UUID, String>? = null,
        fallbackCategoryId: UUID? = null,
    ): List<CheckoutLine> {
        val drafts = checkoutDrafts(items, defaultAccountId = accountId)
        return buildDraftLines(
            drafts = drafts,
            priceText = boxPrices
                ?: drafts.associate { it.itemId to priceInputText(it.unitPrice) },
            tripName = tripName,
            fallbackCategoryId = fallbackCategoryId,
        )
    }

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

        val lines = draftLines(items)

        assertEquals(2, lines.size)
        // One shape only: the trip names the row, the item it came from is the description.
        assertEquals(listOf("Weekly groceries", "Weekly groceries"), lines.map { it.title })
        assertEquals(listOf("Milk", "Bread"), lines.map { it.description })
        assertEquals(8.0, lines[0].amount, 0.0001)
        assertEquals(3.0, lines[1].amount, 0.0001)
    }

    @Test
    fun `a price entered at the till beats the price stored on the item`() {
        val item = item(name = "Milk", unitPrice = 4.0, isChecked = true)

        // The box arrives pre-filled with the stored 4.0; the user types 6, and 6 is what posts.
        val preFilled = draftLines(listOf(item))
        val typed = draftLines(listOf(item), boxPrices = mapOf(item.id to "6"))

        assertEquals(4.0, preFilled.single().amount, 0.0001)
        assertEquals(6.0, typed.single().amount, 0.0001)
    }

    @Test
    fun `an unpriced item is skipped instead of posting a zero expense`() {
        // A ticked item with no price leaves an empty box, and an empty box posts nothing at all.
        val items = listOf(item(name = "Milk", unitPrice = null, isChecked = true))

        assertTrue(draftLines(items).isEmpty())
    }

    @Test
    fun `unticked items never reach the ledger`() {
        val items = listOf(item(name = "Milk", unitPrice = 4.0, isChecked = false))

        assertTrue(draftLines(items).isEmpty())
    }

    @Test
    fun `the item category wins over the list default`() {
        val listDefaultCategoryId = UUID.randomUUID()
        val items = listOf(
            item(name = "Milk", unitPrice = 1.0, isChecked = true, categoryId = categoryId),
            item(name = "Bread", unitPrice = 1.0, isChecked = true, categoryId = null),
        )

        val lines = draftLines(items, fallbackCategoryId = listDefaultCategoryId)

        assertEquals(categoryId, lines.first { it.description == "Milk" }.categoryId)
        assertEquals(listDefaultCategoryId, lines.first { it.description == "Bread" }.categoryId)
    }

    @Test
    fun `the priced line is remembered back onto the item`() {
        assertEquals(2.5, unitPriceFromLine(amount = 5.0, quantity = 2.0), 0.0001)
    }

    @Test
    fun `a zero quantity does not divide by zero`() {
        assertEquals(5.0, unitPriceFromLine(amount = 5.0, quantity = 0.0), 0.0001)
    }

    // ------------------------------------------------------------ which list the screen loads

    @Test
    fun `the detail screen loads the list it was opened with`() {
        val opened = UUID.randomUUID()

        assertTrue(shouldLoadList(loadedListId = null, requestedListId = opened))
    }

    @Test
    fun `the detail screen loads again when it is shown for another list`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        assertTrue(shouldLoadList(loadedListId = first, requestedListId = second))
    }

    @Test
    fun `the detail screen does not reload the list it already shows`() {
        val listId = UUID.randomUUID()

        assertFalse(shouldLoadList(loadedListId = listId, requestedListId = listId))
        assertFalse(shouldLoadList(loadedListId = null, requestedListId = null))
    }

    // ------------------------------------------------------------ setting a price and a quantity

    @Test
    fun `a typed price is accepted with a dot or a comma`() {
        assertEquals(PriceEdit.Priced(4.5), parsePriceEdit("4.5"))
        assertEquals(PriceEdit.Priced(4.5), parsePriceEdit(" 4,5 "))
        assertEquals(PriceEdit.Priced(12.0), parsePriceEdit("12"))
    }

    @Test
    fun `an empty price box clears the price on purpose`() {
        assertEquals(PriceEdit.Cleared, parsePriceEdit(""))
        assertEquals(PriceEdit.Cleared, parsePriceEdit("   "))
    }

    @Test
    fun `garbage in the price box is refused instead of clearing the price`() {
        assertEquals(PriceEdit.Invalid, parsePriceEdit("abc"))
        assertEquals(PriceEdit.Invalid, parsePriceEdit("0"))
        assertEquals(PriceEdit.Invalid, parsePriceEdit("-4"))
        assertEquals(PriceEdit.Invalid, parsePriceEdit("NaN"))
    }

    @Test
    fun `a quantity must be a positive number`() {
        assertEquals(2.0, parseQuantityInput("2")!!, 0.0001)
        assertEquals(1.5, parseQuantityInput("1,5")!!, 0.0001)
        assertNull(parseQuantityInput(""))
        assertNull(parseQuantityInput("0"))
        assertNull(parseQuantityInput("-1"))
        assertNull(parseQuantityInput("two"))
    }

    // ------------------------------------------------------------ when a trip is finished

    @Test
    fun `a trip with an item that was never posted stays open`() {
        val posted = UUID.randomUUID()
        val items = listOf(
            item(name = "Milk", isChecked = true, convertedTransactionId = posted),
            // Ticked but never priced, therefore never posted: this is the item the owner would
            // otherwise lose when the trip disappears.
            item(name = "Mystery item", isChecked = true, convertedTransactionId = null),
        )

        assertFalse(shouldArchiveTrip(items))
    }

    @Test
    fun `a trip is finished once every item is in the ledger`() {
        val posted = UUID.randomUUID()
        val items = listOf(
            item(name = "Milk", isChecked = true, convertedTransactionId = posted),
            item(name = "Bread", isChecked = true, convertedTransactionId = posted),
        )

        assertTrue(shouldArchiveTrip(items))
    }

    @Test
    fun `an empty trip is never archived by posting`() {
        assertFalse(shouldArchiveTrip(emptyList()))
    }

    @Test
    fun `only items without a transaction are still owed a ledger row`() {
        val posted = UUID.randomUUID()
        val items = listOf(
            item(name = "Milk", convertedTransactionId = posted),
            item(name = "Mystery item", convertedTransactionId = null),
        )

        assertEquals(listOf("Mystery item"), items.unpostedItems().map { it.name })
    }
}
