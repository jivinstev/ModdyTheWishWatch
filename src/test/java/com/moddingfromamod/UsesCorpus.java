package com.moddingfromamod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;

/**
 * <b>This test reads a corpus through {@link CorpusSource}</b> (§5): the JUnit tag {@value #TAG}, so another build
 * that runs the core's tests (the house build, from the test kit) can pick exactly these and hand them its real corpus
 * ({@code -Dmoddy.corpus.dir}). {@code UsesCorpusTagTest} keeps it true: every test class that reads through
 * {@code CorpusSource} carries it, and no other does.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag(UsesCorpus.TAG)
public @interface UsesCorpus {

    /** The tag a build filters on: {@code useJUnitPlatform { includeTags 'uses-corpus' }}. */
    String TAG = "uses-corpus";
}
