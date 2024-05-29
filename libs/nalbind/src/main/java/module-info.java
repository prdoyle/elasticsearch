module org.elasticsearch.nalbind {
    exports org.elasticsearch.nalbind.injector;
    exports org.elasticsearch.nalbind.injector.spec;
    exports org.elasticsearch.nalbind.injector.spi to org.elasticsearch.nalbind.impl;
    requires org.elasticsearch.base;
    requires org.elasticsearch.logging;
}
