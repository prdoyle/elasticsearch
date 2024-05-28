package org.elasticsearch.nalbind.api;

/**
 * A marker interface declaring that an object can participate in dependency injection.
 * <p>
 * To use this, add lines like the following to your <code>module-info.java</code>:
 *
 * <pre>
 * // Provides access to InjectableSingleton
 * requires nalbind.api;
 *
 * // Lets Nalbind find and call constructors
 * opens example.myModule to nalbind.injector;
 *
 * // Tells Nalbind which classes to instantiate
 * provides InjectableSingleton with MyExampleServiceImpl;
 * </pre>
 */
public interface InjectableSingleton {
}
