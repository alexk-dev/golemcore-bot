package me.golemcore.bot.plugin.builtin.telegram.inbound;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Small test helper to wrap an instance as an {@link ObjectProvider}.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
final class TestObjectProvider<T> implements ObjectProvider<T> {

    private final T value;

    TestObjectProvider(T value) {
        this.value = value;
    }

    @Override
    public T getObject(Object... args) {
        return getIfAvailable();
    }

    @Override
    public T getIfAvailable() {
        return value;
    }

    @Override
    public T getIfUnique() {
        T available = getIfAvailable();
        return available != null ? available : null;
    }

    @Override
    public java.util.stream.Stream<T> stream() {
        return java.util.stream.Stream.of(value);
    }

    @Override
    public java.util.stream.Stream<T> orderedStream() {
        return java.util.stream.Stream.of(value);
    }
}
