import org.elasticsearch.nalbind.injector.spi.ProxyBytecodeGenerator;
import org.elasticsearch.nalbind.injector.impl.ProxyBytecodeGeneratorImpl;

module org.elasticsearch.nalbind.impl {
    requires org.elasticsearch.nalbind;
    requires org.elasticsearch.logging;
    requires org.objectweb.asm;
    requires java.desktop;
    requires org.elasticsearch.plugin.scanner;

    provides ProxyBytecodeGenerator with ProxyBytecodeGeneratorImpl;
}
