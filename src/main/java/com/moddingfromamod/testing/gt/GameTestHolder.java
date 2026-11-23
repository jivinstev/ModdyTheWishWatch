package com.moddingfromamod.testing.gt;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <b>A class of Gate B tests</b>. {@link #value} is the namespace — the SHARD
 * ({@code testing/GateShards}): {@code -Dneoforge.enabledGameTestNamespaces} picks which run, as it did.
 * Test ids are {@code <namespace>:<class>.<method>}, lowercased — the ids the gate history has always
 * kept, so no test loses its record across the bump. Templates are never prefixed with the class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface GameTestHolder {
    String value();
}
