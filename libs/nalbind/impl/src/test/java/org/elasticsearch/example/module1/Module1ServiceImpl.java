package org.elasticsearch.example.module1;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Set;
import org.elasticsearch.example.module1.api.Module1Listener;
import org.elasticsearch.example.module1.api.Module1Service;
import org.elasticsearch.nalbind.api.InjectableSingleton;
import org.elasticsearch.nalbind.api.Injected;

import static java.util.Collections.newSetFromMap;

public class Module1ServiceImpl implements Module1Service, InjectableSingleton {
	final Set<Module1Listener> listeners = newSetFromMap(new IdentityHashMap<>());

	@Override
	public String greeting() {
		return "Hello from " + getClass().getSimpleName() + " to my " + listeners.size() + " listeners";
	}

	@Injected
	public void registerListeners(Collection<Module1Listener> listeners) {
		this.listeners.addAll(listeners);
	}
}
