import org.elasticsearch.nalbind.injector.spi.ProxyBytecodeGenerator;
import org.elasticsearch.nalbind.injector.impl.ProxyBytecodeGeneratorImpl;

module org.elasticsearch.nalbind.impl {
    requires org.elasticsearch.nalbind;
    requires org.elasticsearch.logging;
    requires org.objectweb.asm;
    requires java.desktop;

    provides ProxyBytecodeGenerator with ProxyBytecodeGeneratorImpl;
}
