package org.elasticsearch.nalbind.test;

import java.util.concurrent.atomic.AtomicReference;
import org.elasticsearch.nalbind.injector.ProxyFactory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.openjdk.jmh.annotations.Mode.Throughput;

@Warmup(time = 2000, timeUnit = MILLISECONDS, iterations = 3)
@Measurement(time = 2000, timeUnit = MILLISECONDS, iterations = 2)
@Fork(1)
@BenchmarkMode(Throughput)
public class ProxyBench {
	static final int ITERS = 10;

	public interface TestInterface {
		String testMethod(String arg);
	}

	public static final class TestImplementation implements TestInterface {
		@Override
		public String testMethod(String arg) {
			return arg;
		}
	}

	static final class VolatileProxy implements TestInterface {
		volatile TestInterface target;

		public VolatileProxy(TestImplementation target) {
			this.target = target;
		}

		@Override
		public String testMethod(String arg) {
			return target.testMethod(arg);
		}
	}

	static final class AtomicProxy implements TestInterface {
		final AtomicReference<TestInterface> ref;

		public AtomicProxy(TestImplementation target) {
			this.ref = new AtomicReference<>(target);
		}

		@Override
		public String testMethod(String arg) {
			return ref.get().testMethod(arg);
		}
	}

	/**
	 * Not a valid implementation. Just for comparison.
	 */
	static final class FinalProxy implements TestInterface {
		final TestInterface target;

		FinalProxy(TestInterface target) {
			this.target = target;
		}

		@Override
		public String testMethod(String arg) {
			return target.testMethod(arg);
		}
	}

	/**
	 * Not a valid implementation. Just for comparison.
	 */
	record RecordProxy(TestInterface target) implements TestInterface {
		@Override
		public String testMethod(String arg) {
			return target.testMethod(arg);
		}
	}

	@State(Scope.Benchmark)
	public static class BenchmarkState {
		final TestInterface finalField;
		TestInterface regularField;
		volatile TestInterface volatileField;
		final TestInterface volatileProxy;
		final TestInterface atomicProxy;
		final TestInterface finalProxy;
		final TestInterface recordProxy;
		final TestInterface indyProxy; // The real one

		public BenchmarkState() {
			TestImplementation target = new TestImplementation();
			this.finalField = target;
			this.regularField = target;
			this.volatileField = target;
			this.volatileProxy = new VolatileProxy(target);
			this.atomicProxy = new AtomicProxy(target);
			this.finalProxy = new FinalProxy(target);
			this.recordProxy = new RecordProxy(target);
			var proxyInfo = ProxyFactory.generateFor(TestInterface.class);
			this.indyProxy = proxyInfo.proxyObject();
			proxyInfo.setter().accept(target);
		}
	}

	@Benchmark
	public void volatileProxy(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.volatileProxy.testMethod("arg"));
		}
	}

	@Benchmark
	public void atomicProxy(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.atomicProxy.testMethod("arg"));
		}
	}

	@Benchmark
	public void indyProxy(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.indyProxy.testMethod("arg"));
		}
	}

//	@Benchmark
	public void x_finalProxy(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.finalProxy.testMethod("arg"));
		}
	}

	@Benchmark
	public void x_recordProxy(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.recordProxy.testMethod("arg"));
		}
	}

	@Benchmark
	public void x_baseline(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state);
		}
	}

	@Benchmark
	public void x_finalField(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.finalField.testMethod("arg"));
		}
	}

//	@Benchmark
	public void x_regularField(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.regularField.testMethod("arg"));
		}
	}

//	@Benchmark
	public void x_volatileField(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.volatileField.testMethod("arg"));
		}
	}

}
