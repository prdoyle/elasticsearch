import org.elasticsearch.nalbind.injector.ProxyFactory;
import org.elasticsearch.nalbind.injector.impl.ProxyFactoryImpl;

module org.elasticsearch.nalbind.impl {
    requires org.elasticsearch.nalbind;
    requires org.elasticsearch.logging;
    requires org.objectweb.asm;

    provides ProxyFactory with ProxyFactoryImpl;
}
