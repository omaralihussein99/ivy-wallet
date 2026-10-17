package com.ivy.shopping

import com.ivy.data.db.entity.ShoppingItemEntity
import com.ivy.data.db.entity.ShoppingListEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * The rules behind the checkout sheet and behind "new trip from template". All of it is pure - no
 * Android, no database - so the shape the ledger will get can be checked without a device.
 */
class ShoppingCheckoutSheetRulesTest {

    private val tripId = UUID.randomUUID()
    private val accountA = UUID.randomUUID()
    private val accountB = UUID.randomUUID()
    private val categoryId = UUID.randomUUID()

    private fun item(
        name: String = "Milk",
        quantity: Double = 1.0,
        unitPrice: Double? = null,
        isChecked: Boolean = true,
        categoryId: UUID? = null,
        convertedTransactionId: UUID? = null,
        checkedAt: Instant? = null,
        listId: UUID = tripId,
    ) = ShoppingItemEntity(
        listId = listId,
        name = name,
        quantity = quantity,
        unitPrice = unitPrice,
        isChecked = isChecked,
        categoryId = categoryId,
        convertedTransactionId = convertedTransactionId,
        checkedAt = checkedAt,
    )

    private fun template(
        name: String = "Weekly groceries",
        isTemplate: Boolean? = true,
    ) = ShoppingListEntity(
        name = name,
        isTemplate = isTemplate,
        color = 0,
    )

    // ------------------------------------------------------------ templates vs trips

    @Test
    fun `templates are the rows flagged as templates`() {
        assertTrue(template(isTemplate = true).isTemplateList())
        assertFalse(template(isTemplate = false).isTemplateList())
        // Nullable on purpose: a row created before templates existed is a trip.
        assertFalse(template(isTemplate = null).isTemplateList())
    }

    @Test
    fun `a trip made from a template is not itself a template`() {
        val trip = template().toNewTrip(id = UUID.randomUUID(), orderNum = 3.0)

        assertFalse(trip.isTemplateList())
        assertFalse(trip.isArchived)
        assertEquals(3.0, trip.orderNum, 0.0001)
    }

    @Test
    fun `starting a trip copies the template items instead of sharing them`() {
        // A template item is never ticked, because the template is never shopped.
        val templateItem = item(name = "Milk", unitPrice = 4.0, isChecked = false)
        val newTripId = UUID.randomUUID()

        val copied = copiedItemsForTrip(listOf(templateItem), tripId = newTripId)

        assertEquals(1, copied.size)
        assertEquals("Milk", copied.single().name)
        assertEquals(newTripId, copied.single().listId)
        assertNotEquals(templateItem.id, copied.single().id)
        assertEquals(4.0, copied.single().unitPrice!!, 0.0001)
        // The template row itself is untouched by the copy.
        assertEquals(tripId, templateItem.listId)
        assertFalse(templateItem.isChecked)
    }

    @Test
    fun `the copied items start a trip with no shopping state`() {
        val templateItem = item(
            isChecked = true,
            convertedTransactionId = UUID.randomUUID(),
            checkedAt = Instant.now(),
        )

        val copied = copiedItemsForTrip(listOf(templateItem), tripId = UUID.randomUUID()).single()

        assertFalse(copied.isChecked)
        assertNull(copied.checkedAt)
        assertNull(copied.convertedTransactionId)
    }

    // ------------------------------------------------------------ the checkout sheet

    @Test
    fun `the sheet has one box per ticked item`() {
        val items = listOf(
            item(name = "Milk", isChecked = true),
            item(name = "Bread", isChecked = false),
            item(name = "Eggs", isChecked = true, convertedTransactionId = UUID.randomUUID()),
        )

        val drafts = checkoutDrafts(items, defaultAccountId = accountA)

        assertEquals(listOf("Milk"), drafts.map { it.name })
    }

    @Test
    fun `a box is pre-filled with the price, the account and the item category`() {
        val items = listOf(
            item(name = "Milk", quantity = 2.0, unitPrice = 4.5, categoryId = categoryId),
        )

        val draft = checkoutDrafts(items, defaultAccountId = accountA).single()

        assertEquals(4.5, draft.unitPrice!!, 0.0001)
        assertEquals(accountA, draft.accountId)
        assertEquals(categoryId, draft.categoryId)
        assertEquals("4.5", priceInputText(draft.unitPrice))
    }

    @Test
    fun `a price box with no stored price starts empty`() {
        assertEquals("", priceInputText(null))
        assertEquals("", priceInputText(0.0))
        assertEquals("4", priceInputText(4.0))
    }

    @Test
    fun `the running total follows the edited prices`() {
        val milk = item(name = "Milk", quantity = 2.0, unitPrice = 4.0)
        val bread = item(name = "Bread", unitPrice = 3.0)
        val drafts = checkoutDrafts(listOf(milk, bread), defaultAccountId = accountA)

        val total = checkoutTotal(
            drafts = drafts,
            priceText = mapOf(milk.id to "5", bread.id to "3.5"),
        )

        assertEquals(13.5, total, 0.0001)
    }

    @Test
    fun `a blank price keeps the item out of the ledger`() {
        val milk = item(name = "Milk", unitPrice = 4.0)
        val bread = item(name = "Bread", unitPrice = 3.0)
        val drafts = checkoutDrafts(listOf(milk, bread), defaultAccountId = accountA)

        val lines = buildDraftLines(
            drafts = drafts,
            priceText = mapOf(milk.id to "", bread.id to "3"),
            tripName = "Weekly groceries",
        )

        assertEquals(listOf("Bread"), lines.map { it.description })
        assertEquals(3.0, lines.single().amount, 0.0001)
    }

    @Test
    fun `checkout waits for a price and a paying account`() {
        val milk = item(name = "Milk", unitPrice = 4.0)

        val assigned = checkoutDrafts(listOf(milk), defaultAccountId = accountA)
        assertEquals(0.0, checkoutTotal(assigned, mapOf(milk.id to "")), 0.0001)
        assertEquals(4.0, checkoutTotal(assigned, mapOf(milk.id to "4")), 0.0001)

        // A price with nobody to pay from is not postable either.
        val unassigned = checkoutDrafts(listOf(milk), defaultAccountId = null)
        assertEquals(0.0, checkoutTotal(unassigned, mapOf(milk.id to "4")), 0.0001)
    }

    @Test
    fun `the ledger row is titled with the trip and stays traceable to its item`() {
        val milk = item(name = "Milk", quantity = 2.0, unitPrice = 4.0, categoryId = categoryId)
        val drafts = checkoutDrafts(listOf(milk), defaultAccountId = accountA)

        val line = buildDraftLines(
            drafts = drafts,
            priceText = mapOf(milk.id to "4"),
            tripName = "Weekly groceries",
        ).single()

        assertEquals("Weekly groceries", line.title)
        assertEquals("Milk", line.description)
        assertEquals(8.0, line.amount, 0.0001)
        assertEquals(categoryId, line.categoryId)
    }

    @Test
    fun `the account shown on the row wins over the fallback`() {
        val milk = item(name = "Milk", unitPrice = 4.0)
        val drafts = checkoutDrafts(listOf(milk), defaultAccountId = accountB)

        val line = buildDraftLines(
            drafts = drafts,
            priceText = mapOf(milk.id to "4"),
            tripName = "Weekly groceries",
            fallbackAccountId = accountA,
        ).single()

        assertEquals(accountB, line.accountId)
    }

    @Test
    fun `no category is a real choice and is posted as such`() {
        val milk = item(name = "Milk", unitPrice = 4.0)
        val drafts = checkoutDrafts(listOf(milk), defaultAccountId = accountA)

        val line = buildDraftLines(
            drafts = drafts,
            priceText = mapOf(milk.id to "4"),
            tripName = "Weekly groceries",
        ).single()

        assertNull(line.categoryId)
    }

    @Test
    fun `a category picked on the row is what gets posted`() {
        val milk = item(name = "Milk", unitPrice = 4.0, categoryId = null)
        val drafts = checkoutDrafts(listOf(milk), defaultAccountId = accountA)
            .map { it.copy(categoryId = categoryId) }

        val line = buildDraftLines(
            drafts = drafts,
            priceText = mapOf(milk.id to "4"),
            tripName = "Weekly groceries",
        ).single()

        assertEquals(categoryId, line.categoryId)
    }

    @Test
    fun `a comma is accepted as a decimal separator`() {
        assertEquals(4.5, parsePriceInput("4,5")!!, 0.0001)
        assertEquals(4.5, parsePriceInput(" 4.5 ")!!, 0.0001)
        assertNull(parsePriceInput(""))
        assertNull(parsePriceInput("abc"))
        assertNull(parsePriceInput("0"))
    }

    @Test
    fun `the only account of the user is pre-selected but several are not`() {
        assertEquals(
            accountA,
            defaultCheckoutAccountId(listAccountId = null, accountIds = listOf(accountA)),
        )
        assertEquals(
            accountA,
            defaultCheckoutAccountId(listAccountId = accountA, accountIds = listOf(accountA, accountB)),
        )
        assertNull(
            defaultCheckoutAccountId(listAccountId = null, accountIds = listOf(accountA, accountB)),
        )
    }

    // ------------------------------------------------------------ one transaction for the trip

    @Test
    fun `one transaction mode posts the whole trip as a single row`() {
        val milk = item(name = "Milk", quantity = 2.0, unitPrice = 4.0)
        val bread = item(name = "Bread", unitPrice = 3.0)
        val eggs = item(name = "Eggs", unitPrice = 2.5)
        val drafts = checkoutDrafts(listOf(milk, bread, eggs), defaultAccountId = accountA)
        val prices = drafts.associate { it.itemId to priceInputText(it.unitPrice) }

        // The per-item shape would write three rows...
        assertEquals(3, buildDraftLines(drafts, prices, tripName = "Weekly groceries").size)

        // ...while this shape writes exactly one.
        val line = buildSingleTransactionLine(
            drafts = drafts,
            priceText = prices,
            tripName = "Weekly groceries",
            accountId = accountA,
            categoryId = categoryId,
        )!!

        assertEquals("Weekly groceries", line.title)
        assertEquals(accountA, line.accountId)
        assertEquals(categoryId, line.categoryId)
    }

    @Test
    fun `the single transaction is the sum of the priced items`() {
        val milk = item(name = "Milk", quantity = 2.0, unitPrice = 4.0)
        val bread = item(name = "Bread", unitPrice = 3.5)
        val drafts = checkoutDrafts(listOf(milk, bread), defaultAccountId = accountA)

        val line = buildSingleTransactionLine(
            drafts = drafts,
            priceText = mapOf(milk.id to "5", bread.id to "3.5"),
            tripName = "Weekly groceries",
            accountId = accountA,
            categoryId = null,
        )!!

        // 5 * 2 + 3.5
        assertEquals(13.5, line.amount, 0.0001)
    }

    @Test
    fun `an unpriced item is left out of the single transaction total`() {
        val milk = item(name = "Milk", unitPrice = 4.0)
        val unpriced = item(name = "Mystery item", unitPrice = null)
        val drafts = checkoutDrafts(listOf(milk, unpriced), defaultAccountId = accountA)

        val line = buildSingleTransactionLine(
            drafts = drafts,
            priceText = mapOf(milk.id to "4", unpriced.id to ""),
            tripName = "Weekly groceries",
            accountId = accountA,
            categoryId = null,
        )!!

        assertEquals(4.0, line.amount, 0.0001)
    }

    @Test
    fun `one transaction mode refuses to post when nothing is priced`() {
        val milk = item(name = "Milk", unitPrice = null)
        val drafts = checkoutDrafts(listOf(milk), defaultAccountId = accountA)

        assertNull(
            buildSingleTransactionLine(
                drafts = drafts,
                priceText = mapOf(milk.id to ""),
                tripName = "Weekly groceries",
                accountId = accountA,
                categoryId = null,
            )
        )
    }

    @Test
    fun `one transaction mode refuses to post without a paying account`() {
        val milk = item(name = "Milk", unitPrice = 4.0)
        val drafts = checkoutDrafts(listOf(milk), defaultAccountId = null)

        assertNull(
            buildSingleTransactionLine(
                drafts = drafts,
                priceText = mapOf(milk.id to "4"),
                tripName = "Weekly groceries",
                accountId = null,
                categoryId = null,
            )
        )
    }

    @Test
    fun `a trip that is already in the ledger cannot post a second transaction`() {
        // Everything the trip posted carries the transaction id it produced.
        val posted = UUID.randomUUID()
        val items = listOf(
            item(name = "Milk", quantity = 2.0, unitPrice = 4.0, convertedTransactionId = posted),
            item(name = "Bread", unitPrice = 3.0, convertedTransactionId = posted),
        )

        // Nothing is postable any more, so the sheet is empty and so is the single row.
        assertTrue(items.postableItems().isEmpty())
        assertTrue(items.unpostedItems().isEmpty())
        val drafts = checkoutDrafts(items, defaultAccountId = accountA)
        assertNull(
            buildSingleTransactionLine(
                drafts = drafts,
                priceText = emptyMap(),
                tripName = "Weekly groceries",
                accountId = accountA,
                categoryId = null,
            )
        )
    }

    @Test
    fun `the single row is pre-filled with the category most items carry`() {
        val milk = item(name = "Milk", categoryId = categoryId)
        val bread = item(name = "Bread", categoryId = categoryId)
        val other = item(name = "Cat food", categoryId = UUID.randomUUID())
        val items = listOf(milk, bread, other)

        assertEquals(categoryId, mostCommonCategoryId(items))
    }

    @Test
    fun `a trip with no item categories pre-fills no category`() {
        assertNull(mostCommonCategoryId(listOf(item(name = "Milk"), item(name = "Bread"))))
    }
}
