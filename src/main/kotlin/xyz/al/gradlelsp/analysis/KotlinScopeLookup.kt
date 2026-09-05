package xyz.al.gradlelsp.analysis

import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered

/** Filter compiler-owned names before resolving descriptors: some scopes ignore descriptor name filters. */
internal fun MemberScope.completionDescriptors(
    kinds: DescriptorKindFilter,
    matches: (Name) -> Boolean,
): List<DeclarationDescriptor> = buildList {
    if (kinds.acceptsKinds(DescriptorKindFilter.FUNCTIONS_MASK)) {
        getFunctionNames().asSequence().filter(matches).forEach { name ->
            addAll(getContributedFunctions(name, NoLookupLocation.FROM_IDE))
        }
    }
    if (kinds.acceptsKinds(DescriptorKindFilter.VARIABLES_MASK)) {
        getVariableNames().asSequence().filter(matches).forEach { name ->
            addAll(getContributedVariables(name, NoLookupLocation.FROM_IDE))
        }
    }
    if (kinds.acceptsKinds(DescriptorKindFilter.CLASSIFIERS_MASK)) {
        val names = getClassifierNames()
        if (names == null) {
            // A null name set means unknown, not empty (notably Java package scopes).
            addAll(getDescriptorsFiltered(kinds.restrictedToKinds(DescriptorKindFilter.CLASSIFIERS_MASK), matches))
        } else {
            names.asSequence().filter(matches).forEach { name ->
                getContributedClassifier(name, NoLookupLocation.FROM_IDE)?.let(::add)
            }
        }
    }
}.filter { kinds.accepts(it) && matches(it.name) }
