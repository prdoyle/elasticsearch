package org.elasticsearch.nalbind.injector;

import java.util.Iterator;
import java.util.List;

/**
 * Indicates an error condition in which a given type can't be injected
 * because there is no clear way to identify the right object for that type.
 *
 * <p>
 * For efficiency of instantiation, this is arranged as a tree; if there are
 * more than two candidates, than either {@link #option1} or {@link #option2}
 * (or both) will themselves be an {@link AmbiguousSpec}.
 */
public record AmbiguousSpec(
	Class<?> requestedType,
	InjectionSpec option1,
	InjectionSpec option2
) implements InjectionSpec {
	public Iterator<UnambiguousSpec> candidates() {
		return new Iterator<>() {
			final Iterator<InjectionSpec> optionIter = List.of(option1, option2).iterator();
			Iterator<UnambiguousSpec> candidateIter = null;

			@Override
			public boolean hasNext() {
				if (optionIter.hasNext()) {
					return true;
				} else if (candidateIter == null) {
					return false;
				} else {
					return candidateIter.hasNext();
				}
			}

			@Override
			public UnambiguousSpec next() {
				if (candidateIter != null && candidateIter.hasNext()) {
					return candidateIter.next();
				}
				var nextOption = optionIter.next();
				if (nextOption instanceof UnambiguousSpec u) {
					candidateIter = null;
					return u;
				} else if (nextOption instanceof AmbiguousSpec a) {
					candidateIter = a.candidates();
					return candidateIter.next(); // Always at least two
				} else {
					throw new AssertionError("Unexpected type: " + nextOption.getClass());
				}
			}
		};
	}
}
