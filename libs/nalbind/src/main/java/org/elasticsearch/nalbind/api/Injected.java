package org.elasticsearch.nalbind.api;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Collection;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a method of an {@link InjectableSingleton} that the injector will invoke
 * after instantiating all the singletons. The method should take a single parameter
 * of type {@link Collection Collection&lt;T&gt;}, and the injector will pass it
 * a collection of all singletons of type <code>T</code> or a subtype.
 *
 * <p>
 * This can be handy to register "listeners" or "callbacks" in situations that would
 * otherwise lead to circular dependencies among the singletons.
 * For example:
 * <p>
 * {@snippet :
 *  public class ExampleSubscriberService implements ExampleListener, InjectableSingleton {
 *		final ExamplePublisherService examplePublisherService;
 *
 * 		@Inject
 * 		ExamplePublisherService(ExamplePublisherService p) {
 * 			 examplePublisherService = p;
 * 		}
 *
 *		@Override
 *		void receiveMessage(ExampleMessage message) {
 * 			 // ...
 *		}
 * }
 *
 * public class ExamplePublisherService implements InjectableSingleton {
 * 		final List<ExampleListener> listeners = new ArrayList<>();
 *
 * 		@Injected
 * 		void registerListeners(Collection<ExampleListener> newListeners) {
 * 			 listeners.addAll(newListeners);
 * 		}
 * }
 *}
 *
 */
@Target(METHOD)
@Retention(RUNTIME)
public @interface Injected {
}
