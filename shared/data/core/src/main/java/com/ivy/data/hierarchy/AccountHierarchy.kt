package com.ivy.data.hierarchy

import com.ivy.data.model.AccountId
import java.math.BigDecimal

/**
 * The rules of the account hierarchy, expressed as pure functions.
 *
 * Nothing here knows about Android, Room, Compose or Ivy's money formatting. A [HierarchyNode] is
 * just an id, an optional parent id and that account's OWN balance (the account alone, its children
 * excluded), so every rule below can be checked on the JVM without a database or a device.
 *
 * The rule that matters the most is the **double-count rule**: a child's balance rolls INTO its
 * parent, and a parent is never counted twice. So the user's grand total is the sum of the
 * rolled-up balances of the ROOT accounts only - the accounts with no parent - and never the sum of
 * every account. Summing every account would count a child twice: once on its own row and once
 * inside its parent.
 *
 * Orphan tolerance: if a child's `parentAccountId` no longer resolves to an account, the child is
 * treated as a root, so roll-up and the total neither crash nor lose the money.
 */
data class HierarchyNode(
    val id: AccountId,
    val parentId: AccountId? = null,
    val ownBalance: BigDecimal = BigDecimal.ZERO,
    val orderNum: Double = 0.0,
    /**
     * The account's own currency (ISO code). It is needed to decide whether mixing this account
     * into a base-currency figure is honest at all - see `HierarchyCurrency.kt`. Defaulted so the
     * original hierarchy rules (which are currency-agnostic) keep working unchanged.
     */
    val currency: String = "",
)

/** A flattened tree row: which account it is, where it sits and what it is worth. */
data class HierarchyRow(
    val id: AccountId,
    val parentId: AccountId?,
    val depth: Int,
    val ownBalance: BigDecimal,
    val rolledUpBalance: BigDecimal,
    val hasChildren: Boolean,
)

/** The outcome of trying to make `newParent` the parent of `account`. */
sealed interface ParentAssignment {
    /** The assignment is safe: it cannot create a self-loop or a cycle. */
    data object Allowed : ParentAssignment

    /** Rejected: an account cannot be its own parent. */
    data object Self : ParentAssignment

    /** Rejected: it would close a loop (a parent cannot be one of its own descendants). */
    data object Cycle : ParentAssignment
}

/**
 * Every account mapped to its effective parent, or `null` when it is a root.
 *
 * `null` covers three cases on purpose: a genuine root, a child whose parent id no longer resolves
 * (orphan) and an account that names itself as its own parent. A cycle among several accounts is
 * also resolved here, by cutting it at the smallest id, so no traversal below can hang on malformed
 * data.
 */
fun effectiveParents(nodes: List<HierarchyNode>): Map<AccountId, AccountId?> {
    val byId = nodes.associateBy { it.id }
    val parents = HashMap<AccountId, AccountId?>(nodes.size)
    nodes.forEach { node ->
        val parentId = node.parentId
        parents[node.id] = when {
            parentId == null -> null
            parentId == node.id -> null
            !byId.containsKey(parentId) -> null
            else -> parentId
        }
    }

    // Malformed data may still hold a cycle (A -> B -> A). It can no longer be created through the
    // UI, but it must not hang roll-up either. Cut it deterministically: the smallest id in the loop
    // becomes a root.
    nodes.forEach { node ->
        val seen = LinkedHashSet<AccountId>()
        var current: AccountId? = node.id
        while (current != null) {
            if (!seen.add(current)) {
                val loop = seen.toList().dropWhile { it != current }
                val cut = loop.minByOrNull { it.value }
                if (cut != null) parents[cut] = null
                break
            }
            current = parents[current]
        }
    }
    return parents
}

private fun childrenByParent(
    nodes: List<HierarchyNode>,
    parents: Map<AccountId, AccountId?>,
): Map<AccountId, List<HierarchyNode>> {
    val children = HashMap<AccountId, MutableList<HierarchyNode>>()
    nodes.forEach { node ->
        val parentId = parents[node.id] ?: return@forEach
        children.getOrPut(parentId) { mutableListOf() }.add(node)
    }
    return children
}

private val hierarchyOrder: Comparator<HierarchyNode> =
    compareBy<HierarchyNode> { it.orderNum }.thenBy { it.id.value }

/**
 * The rolled-up balance of every account: its own balance plus, recursively, the rolled-up balances
 * of its children. Any depth.
 */
fun rolledUpBalances(nodes: List<HierarchyNode>): Map<AccountId, BigDecimal> {
    if (nodes.isEmpty()) return emptyMap()
    val byId = nodes.associateBy { it.id }
    val children = childrenByParent(nodes, effectiveParents(nodes))
    val memo = HashMap<AccountId, BigDecimal>(nodes.size)

    fun rollUp(id: AccountId): BigDecimal {
        memo[id]?.let { return it }
        val node = byId[id] ?: return BigDecimal.ZERO
        var sum = node.ownBalance
        children[id]?.forEach { child ->
            sum = sum.add(rollUp(child.id))
        }
        memo[id] = sum
        return sum
    }

    return nodes.associate { it.id to rollUp(it.id) }
}

/** The ids of the accounts that have no (resolvable) parent. */
fun rootIds(nodes: List<HierarchyNode>): List<AccountId> {
    val parents = effectiveParents(nodes)
    return nodes.filter { parents[it.id] == null }.map { it.id }
}

/**
 * The user's money, obeying the double-count rule: the rolled-up balances of the ROOT accounts only.
 * An account that has a parent is never added here on its own, because it is already part of its
 * parent's rolled-up figure.
 */
fun grandTotal(nodes: List<HierarchyNode>): BigDecimal {
    if (nodes.isEmpty()) return BigDecimal.ZERO
    val rolled = rolledUpBalances(nodes)
    return rootIds(nodes).fold(BigDecimal.ZERO) { total, id ->
        total.add(rolled[id] ?: BigDecimal.ZERO)
    }
}

/** The whole hierarchy flattened to pre-order rows (roots first, each with the depth to indent by). */
fun hierarchyRows(nodes: List<HierarchyNode>): List<HierarchyRow> {
    if (nodes.isEmpty()) return emptyList()
    val byId = nodes.associateBy { it.id }
    val parents = effectiveParents(nodes)
    val children = childrenByParent(nodes, parents)
    val rolled = rolledUpBalances(nodes)
    val rows = ArrayList<HierarchyRow>(nodes.size)

    fun visit(node: HierarchyNode, depth: Int, path: MutableSet<AccountId>) {
        if (!path.add(node.id)) return
        val kids = children[node.id].orEmpty().sortedWith(hierarchyOrder)
        rows += HierarchyRow(
            id = node.id,
            parentId = parents[node.id],
            depth = depth,
            ownBalance = node.ownBalance,
            rolledUpBalance = rolled[node.id] ?: node.ownBalance,
            hasChildren = kids.isNotEmpty(),
        )
        kids.forEach { visit(it, depth + 1, path) }
        path.remove(node.id)
    }

    nodes.filter { parents[it.id] == null }
        .sortedWith(hierarchyOrder)
        .forEach { visit(it, 0, mutableSetOf()) }
    return rows
}

/**
 * Drops the rows hidden because one of their ancestors is collapsed. A collapsed row itself stays
 * visible - collapsing hides its children, not the row.
 */
fun visibleRows(rows: List<HierarchyRow>, collapsed: Set<AccountId>): List<HierarchyRow> {
    if (collapsed.isEmpty()) return rows
    val visible = ArrayList<HierarchyRow>(rows.size)
    // (depth, subtreeHidden) of the ancestors currently open on the way down.
    val stack = ArrayDeque<Pair<Int, Boolean>>()
    rows.forEach { row ->
        while (stack.isNotEmpty() && stack.last().first >= row.depth) stack.removeLast()
        val hidden = stack.lastOrNull()?.second == true
        if (!hidden) visible += row
        stack.addLast(row.depth to (hidden || row.id in collapsed))
    }
    return visible
}

/** True when [candidate] is [ancestor] itself or sits anywhere below it in the hierarchy. */
fun isDescendant(nodes: List<HierarchyNode>, ancestor: AccountId, candidate: AccountId): Boolean {
    if (ancestor == candidate) return true
    val parents = effectiveParents(nodes)
    val seen = HashSet<AccountId>()
    var current = parents[candidate]
    while (current != null && seen.add(current)) {
        if (current == ancestor) return true
        current = parents[current]
    }
    return false
}

/**
 * The backstop behind the parent picker: decides whether `newParent` may become the parent of
 * `account`. `null` (clearing the parent) is always allowed.
 */
fun validateParentAssignment(
    nodes: List<HierarchyNode>,
    account: AccountId,
    newParent: AccountId?,
): ParentAssignment = when {
    newParent == null -> ParentAssignment.Allowed
    newParent == account -> ParentAssignment.Self
    isDescendant(nodes, ancestor = account, candidate = newParent) -> ParentAssignment.Cycle
    else -> ParentAssignment.Allowed
}

/** Convenience predicate for [validateParentAssignment], kept for call sites that only care about cycles. */
fun wouldCreateCycle(nodes: List<HierarchyNode>, account: AccountId, newParent: AccountId?): Boolean =
    validateParentAssignment(nodes, account, newParent) == ParentAssignment.Cycle

/**
 * The accounts the picker may offer as a parent for [account]: everything except the account itself
 * and its own descendants. Keeping those out is what makes a cycle impossible through the UI.
 */
fun parentCandidates(nodes: List<HierarchyNode>, account: AccountId): List<AccountId> =
    nodes.map { it.id }
        .filter { it != account && !isDescendant(nodes, ancestor = account, candidate = it) }
