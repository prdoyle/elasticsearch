### Entitlement Trampoline

This is a small code stub that is loaded into the boot classloader by the entitlement agent
so that it is callable from the class library methods instrumented by the agent.
Its job is just to forward the entitlement checks to the actual runtime library,
which is loaded normally.
