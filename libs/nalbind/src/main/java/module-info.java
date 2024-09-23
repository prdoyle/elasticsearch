module org.elasticsearch.nalbind {
    exports org.elasticsearch.nalbind.injector to org.elasticsearch.server;
    exports org.elasticsearch.nalbind.injector.spi;
    exports org.elasticsearch.nalbind.injector.runtime to org.elasticsearch.nalbind.impl;
    uses org.elasticsearch.nalbind.injector.spi.ProxyBytecodeGenerator;
    requires org.elasticsearch.base;
    requires org.elasticsearch.logging;
}
