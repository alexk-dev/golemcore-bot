package me.golemcore.bot.adapter.inbound.telegram;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Small test helper to wrap an instance as an {@link ObjectProvider}.
 */
final class TestObjectProvider<T> implements ObjectProvider<T> {

    private final T value;

    TestObjectProvider(T value) {
        this.value = value;
    }

    @Override
    public T getObject(Object... args) {
        return value;
    }

    @Override
    public T getIfAvailable() {
        return value;
    }

    @Override
    public T getIfUnique() {
        return value;
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
