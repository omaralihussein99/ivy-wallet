package com.ivy.data.hierarchy

import com.ivy.data.model.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * The account-hierarchy rules are pure, so they are checked here on the JVM - no database, no device.
 */
class AccountHierarchyTest {

    private fun id(n: Long): AccountId = AccountId(UUID(0L, n))

    private fun money(value: String): BigDecimal = BigDecimal(value)

    private fun assertMoney(expected: String, actual: BigDecimal) {
        assertEquals("expected $expected but was $actual", 0, money(expected).compareTo(actual))
    }

    private fun node(
        accountId: AccountId,
        parent: AccountId? = null,
        own: String = "0",
        orderNum: Double = 0.0,
    ) = HierarchyNode(
        id = accountId,
        parentId = parent,
        ownBalance = money(own),
        orderNum = orderNum,
    )

    // ------------------------------------------------------------ roll-up

    @Test
    fun `a parent rolls up its own balance plus its children recursively`() {
        val root = id(1)
        val child = id(2)
        val grandChild = id(3)
        val nodes = listOf(
            node(root, own = "100"),
            node(child, parent = root, own = "30"),
            node(grandChild, parent = child, own = "5"),
        )

        val rolled = rolledUpBalances(nodes)

        // The root's OWN balance is 100, but its rolled-up balance also contains its children: 135.
        assertMoney("100", nodes.first { it.id == root }.ownBalance)
        assertMoney("135", rolled.getValue(root)) // 100 + 30 + 5
        assertMoney("35", rolled.getValue(child))
        assertMoney("5", rolled.getValue(grandChild))
    }

    // ------------------------------------------------------------ the double-count rule

    @Test
    fun `the grand total is the rolled up balances of the roots only and never counts a parent twice`() {
        val parent = id(1)
        val kid = id(2)
        val nodes = listOf(
            node(parent, own = "100"),
            node(kid, parent = parent, own = "30"),
        )

        val rolled = rolledUpBalances(nodes)
        val total = grandTotal(nodes)

        // The child's 30 is inside the parent's rolled-up figure ...
        assertMoney("130", rolled.getValue(parent))
        assertMoney("30", rolled.getValue(kid))

        // ... so the correct total is the parent's rolled-up balance alone.
        assertMoney("130", total)

        // If the total were naively "the sum of every account's shown balance", the child would be
        // counted twice - once on its own row and once inside its parent - and the total would be
        // wrong. THIS is the double-count rule.
        val naiveSumOfEveryAccountsShownBalance = nodes.sumOf { rolled.getValue(it.id) }
        assertMoney("160", naiveSumOfEveryAccountsShownBalance)
        assertNotEquals(
            "the total must not double count the child",
            naiveSumOfEveryAccountsShownBalance,
            total,
        )

        // The invariant the double-count rule protects: the money is counted exactly once.
        val sumOfOwnBalances = nodes.fold(BigDecimal.ZERO) { acc, it -> acc.add(it.ownBalance) }
        assertMoney("130", sumOfOwnBalances)
    }

    @Test
    fun `a deeper tree still counts every account exactly once`() {
        val a = id(1)
        val b = id(2)
        val c = id(3)
        val d = id(4)
        val nodes = listOf(
            node(a, own = "10"),
            node(b, parent = a, own = "20"),
            node(c, parent = b, own = "30"),
            node(d, parent = c, own = "40"),
        )

        assertMoney("100", rolledUpBalances(nodes).getValue(a))
        assertMoney("100", grandTotal(nodes))
        assertEquals(listOf(a), rootIds(nodes))
    }

    // ------------------------------------------------------------ roots

    @Test
    fun `an account with a parent is not part of the root set`() {
        val parent = id(1)
        val kid = id(2)
        val lonely = id(3)
        val nodes = listOf(
            node(parent, own = "1"),
            node(kid, parent = parent, own = "2"),
            node(lonely, own = "3"),
        )

        val roots = rootIds(nodes)

        assertTrue(rootIds(nodes).contains(parent))
        assertTrue(roots.contains(lonely))
        assertFalse("a child must never be a root", roots.contains(kid))
        assertMoney("6", grandTotal(nodes)) // 1 + 2 + 3, each counted once
    }

    // ------------------------------------------------------------ cycles

    @Test
    fun `an account cannot be its own parent`() {
        val a = id(1)
        val nodes = listOf(node(a, own = "5"))

        assertEquals(ParentAssignment.Self, validateParentAssignment(nodes, account = a, newParent = a))
        assertFalse(wouldCreateCycle(nodes, account = a, newParent = a))
    }

    @Test
    fun `a stored self parent does not hang the hierarchy and behaves like a root`() {
        val a = id(1)
        val nodes = listOf(node(a, parent = a, own = "5"))

        assertTrue(rootIds(nodes).contains(a))
        assertMoney("5", rolledUpBalances(nodes).getValue(a))
        assertMoney("5", grandTotal(nodes))
    }

    @Test
    fun `a direct cycle is rejected`() {
        val a = id(1)
        val b = id(2)
        // b already sits under a.
        val nodes = listOf(node(a, own = "1"), node(b, parent = a, own = "2"))

        // b can become a child of a (it is not a cycle) ...
        assertEquals(ParentAssignment.Allowed, validateParentAssignment(nodes, account = b, newParent = a))

        // ... but a cannot become a child of b: that would close a -> b -> a.
        assertEquals(ParentAssignment.Cycle, validateParentAssignment(nodes, account = a, newParent = b))
        assertTrue(wouldCreateCycle(nodes, account = a, newParent = b))
    }

    @Test
    fun `a deep cycle is rejected`() {
        val a = id(1)
        val b = id(2)
        val c = id(3)
        // a -> b -> c
        val nodes = listOf(
            node(a, own = "1"),
            node(b, parent = a, own = "2"),
            node(c, parent = b, own = "3"),
        )

        // Making a the child of its own grandchild would close a -> b -> c -> a.
        assertEquals(ParentAssignment.Cycle, validateParentAssignment(nodes, account = a, newParent = c))
        // Same for the middle one.
        assertEquals(ParentAssignment.Cycle, validateParentAssignment(nodes, account = b, newParent = c))
        // And a sibling-free move is still fine.
        assertEquals(ParentAssignment.Allowed, validateParentAssignment(nodes, account = c, newParent = a))
    }

    @Test
    fun `clearing the parent is always allowed`() {
        val a = id(1)
        val b = id(2)
        val nodes = listOf(node(a, own = "1"), node(b, parent = a, own = "2"))

        assertEquals(ParentAssignment.Allowed, validateParentAssignment(nodes, account = b, newParent = null))
        assertFalse(wouldCreateCycle(nodes, account = b, newParent = null))
    }

    @Test
    fun `a malformed stored cycle does not hang the hierarchy and the money is still counted once`() {
        val a = id(1)
        val b = id(2)
        // Corrupt data: a -> b -> a. It cannot be created through the UI, but it may already be stored.
        val nodes = listOf(node(a, parent = b, own = "10"), node(b, parent = a, own = "20"))

        val roots = rootIds(nodes)
        assertEquals("the loop is cut at exactly one point", 1, roots.size)
        assertMoney("30", grandTotal(nodes))

        val rows = hierarchyRows(nodes)
        assertEquals(2, rows.size)
        assertEquals(listOf(0, 1), rows.map { it.depth })
    }

    // ------------------------------------------------------------ orphans

    @Test
    fun `a child whose parent no longer resolves is treated as a root and does not crash`() {
        val parent = id(1)
        val orphan = id(2)
        val missingParent = id(99)
        val nodes = listOf(
            node(parent, own = "100"),
            node(orphan, parent = missingParent, own = "50"),
        )

        val roots = rootIds(nodes)

        assertTrue(roots.contains(parent))
        assertTrue("an orphan must be treated as a root", roots.contains(orphan))
        assertMoney("150", grandTotal(nodes)) // 100 + 50, nothing lost
        assertMoney("50", rolledUpBalances(nodes).getValue(orphan))

        val rows = hierarchyRows(nodes)
        assertEquals(2, rows.size)
        assertTrue("an orphan is shown at the root depth", rows.all { it.depth == 0 })
    }

    // ------------------------------------------------------------ the picker

    @Test
    fun `the parent picker excludes the account itself and all of its descendants`() {
        val a = id(1)
        val b = id(2)
        val c = id(3)
        val d = id(4)
        // a -> b -> c, plus an unrelated d
        val nodes = listOf(
            node(a, own = "1"),
            node(b, parent = a, own = "2"),
            node(c, parent = b, own = "3"),
            node(d, own = "4"),
        )

        val candidates = parentCandidates(nodes, account = a)

        assertFalse("the account itself is not a candidate", candidates.contains(a))
        assertFalse("a child is not a candidate", candidates.contains(b))
        assertFalse("a grandchild is not a candidate", candidates.contains(c))
        assertTrue("an unrelated account is a candidate", candidates.contains(d))

        // And every candidate the picker offers is safe to assign.
        candidates.forEach {
            assertEquals(
                "candidate $it must be safe",
                ParentAssignment.Allowed,
                validateParentAssignment(nodes, account = a, newParent = it),
            )
        }
    }

    // ------------------------------------------------------------ rows and expand/collapse

    @Test
    fun `the tree is flattened with roots first and children indented beneath their parent`() {
        val a = id(1)
        val b = id(2)
        val c = id(3)
        val nodes = listOf(
            node(a, own = "1", orderNum = 0.0),
            node(b, parent = a, own = "2", orderNum = 1.0),
            node(c, parent = b, own = "3", orderNum = 2.0),
        )

        val rows = hierarchyRows(nodes)

        assertEquals(listOf(a, b, c), rows.map { it.id })
        assertEquals(listOf(0, 1, 2), rows.map { it.depth })
        assertTrue(rows.first().hasChildren)
        assertFalse(rows.last().hasChildren)
        assertMoney("6", rows.first().rolledUpBalance)
        assertMoney("1", rows.first().ownBalance)
    }

    @Test
    fun `collapsing a parent hides its whole subtree`() {
        val a = id(1)
        val b = id(2)
        val c = id(3)
        val d = id(4)
        val nodes = listOf(
            node(a, own = "1", orderNum = 0.0),
            node(b, parent = a, own = "2", orderNum = 1.0),
            node(c, parent = b, own = "3", orderNum = 2.0),
            node(d, own = "4", orderNum = 3.0),
        )
        val rows = hierarchyRows(nodes)

        assertEquals(listOf(a, b, c, d), visibleRows(rows, collapsed = emptySet()).map { it.id })
        // Collapsing the middle parent keeps it visible and hides only its own subtree.
        assertEquals(listOf(a, b, d), visibleRows(rows, collapsed = setOf(b)).map { it.id })
        // Collapsing the root hides everything below it.
        assertEquals(listOf(a, d), visibleRows(rows, collapsed = setOf(a)).map { it.id })
        // Collapsing a leaf changes nothing.
        assertEquals(listOf(a, b, c, d), visibleRows(rows, collapsed = setOf(c)).map { it.id })
    }
}
