package com.moddingfromamod.testing;

import java.util.Comparator;

import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;

/** {@code -Ptestorder=reverse} (gate-a.gradle): test classes in reverse name order, for the order-independence check. */
public final class ReverseClassOrder implements ClassOrderer {

    @Override
    public void orderClasses(ClassOrdererContext context) {
        context.getClassDescriptors().sort(
                Comparator.comparing((ClassDescriptor d) -> d.getTestClass().getName()).reversed());
    }
}
