module org.elasticsearch.nalbind {
    exports org.elasticsearch.nalbind.injector;
    exports org.elasticsearch.nalbind.injector.spec;
    exports org.elasticsearch.nalbind.injector.spi;
    exports org.elasticsearch.nalbind.api;
    uses org.elasticsearch.nalbind.injector.spi.ProxyBytecodeGenerator;
    requires org.elasticsearch.base;
    requires org.elasticsearch.logging;
}
