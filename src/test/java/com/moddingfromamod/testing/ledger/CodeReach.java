package com.moddingfromamod.testing.ledger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * <b>The code a check can run, hashed</b> (phase 2): the methods of OUR classes reachable from a check's test
 * method, found by reading the bytecode, and one digest over what each of them does ({@link ClassFile.Method#digest}).
 *
 * <p>Why a reach and not a list of packages, and not the class closure: measured on this tree, the classes
 * {@code BuilderLowering} merely NAMES, followed to the end, are 1935 of 2851 — the whole mod, because a builder class
 * mentions a wish class that mentions the agent. Almost none of that runs in a Builder check, and a fingerprint over it
 * would be invalidated by every commit. A reach over METHODS follows only what is called: a static or special call goes
 * to its declaration; a virtual call to its declaration up the hierarchy; and every class the reached code instantiates
 * (or names as a class literal, which reflection can instantiate) has ALL its methods reached, which is how a callback
 * the JDK makes ({@code compare}, {@code apply}, {@code toString}) is never missed. Touching a class's statics reaches
 * its static initialiser. A lambda's body is reached through the invokedynamic that captures it.
 *
 * <p>What it cannot see, said plainly, and what catches it: code reached by reflection from a NAME (a
 * {@code Class.forName} of a string, a ServiceLoader), and anything that is not bytecode — a resource file is an input
 * the check declares ({@link GateLedger}). Library code is not hashed method by method; the test classpath's jar names
 * are (a version bump changes a name). The stale-pass re-verification ({@link GateLedger}: a nightly slice, a release
 * everything) is what catches a dependency this misses, and names it.
 */
public final class CodeReach {

    /** Our classes: the only ones whose methods are followed and hashed. */
    public static final String OURS = "com/moddingfromamod/";

    private final Function<String, byte[]> bytes;
    private final Map<String, ClassFile> parsed = new HashMap<>();

    /** {@code bytes}: internal class name → its class file, or null when there is none (a JDK or library class). */
    public CodeReach(Function<String, byte[]> bytes) {
        this.bytes = bytes;
    }

    /** The class files the test JVM itself runs. */
    public static CodeReach ofClasspath() {
        ClassLoader cl = CodeReach.class.getClassLoader();
        return new CodeReach(name -> {
            if (!name.startsWith(OURS)) {
                return null;
            }
            try (InputStream in = cl.getResourceAsStream(name + ".class")) {
                return in == null ? null : in.readAllBytes();
            } catch (IOException e) {
                return null;
            }
        });
    }

    ClassFile cls(String name) {
        if (name == null || !name.startsWith(OURS)) {
            return null;
        }
        return parsed.computeIfAbsent(name, n -> {
            byte[] b = bytes.apply(n);
            return b == null ? null : ClassFile.read(b);
        });
    }

    /** What a reach found: the methods (owner.name+desc → digest) and the classes they belong to. */
    public record Reached(Map<String, byte[]> methods, Set<String> classes) {

        /** One hash over every reached method's digest and each reached class's place in the hierarchy. */
        public String digest(CodeReach in) {
            MessageDigest md = ClassFile.sha();
            methods.forEach((k, d) -> {
                md.update(k.getBytes(StandardCharsets.UTF_8));
                md.update(d);
            });
            for (String c : classes) {
                ClassFile cf = in.cls(c);
                md.update(("class " + c + " extends " + cf.superName + " implements " + cf.interfaces + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return GateLedger.hex(md.digest());
        }

        /** The source files these classes compile from, relative to the repo (inner classes fold into their file). */
        public Set<String> sourceFiles() {
            Set<String> out = new TreeSet<>();
            for (String c : classes) {
                String top = c.contains("$") ? c.substring(0, c.indexOf('$')) : c;
                out.add(top + ".java");
            }
            return out;
        }
    }

    /** Every method reachable from the methods of {@code owner} named one of {@code roots} (all overloads). */
    public Reached from(String owner, Set<String> roots) {
        Map<String, byte[]> methods = new TreeMap<>();
        Set<String> classes = new TreeSet<>();
        Set<String> instantiated = new HashSet<>();
        Set<String> initialised = new HashSet<>();
        Deque<String[]> todo = new ArrayDeque<>();   // {owner, name+desc}
        ClassFile root = cls(owner);
        if (root == null) {
            throw new IllegalArgumentException("no class file for " + owner);
        }
        for (ClassFile.Method m : root.methods.values()) {
            if (roots.contains(m.name())) {
                todo.add(new String[] {owner, m.name() + m.desc()});
            }
        }
        if (todo.isEmpty()) {
            throw new IllegalArgumentException(owner + " has no method named " + roots);
        }
        while (!todo.isEmpty()) {
            String[] next = todo.poll();
            ClassFile cf = cls(next[0]);
            if (cf == null) {
                continue;
            }
            String key = next[0] + "." + next[1];
            ClassFile.Method m = cf.methods.get(next[1]);
            if (m == null || methods.containsKey(key)) {
                continue;
            }
            methods.put(key, m.digest());
            classes.add(next[0]);
            for (ClassFile.Ref r : m.refs()) {
                switch (r.kind()) {
                    case CALL_EXACT -> {
                        initialise(r.owner(), initialised, todo, classes);
                        String[] decl = declaration(r.owner(), r.name() + r.desc());
                        if (decl != null) {
                            todo.add(decl);
                        }
                    }
                    case CALL_VIRTUAL -> {
                        String[] decl = declaration(r.owner(), r.name() + r.desc());
                        if (decl != null) {
                            todo.add(decl);
                        }
                    }
                    case NEW, CLASS_LITERAL -> instantiate(r.owner(), instantiated, initialised, todo, classes);
                    case STATIC -> initialise(r.owner(), initialised, todo, classes);
                    default -> {
                    }
                }
            }
        }
        return new Reached(methods, classes);
    }

    /** The class that declares {@code sig} for a call on {@code owner}: up the superclasses, then the interfaces. */
    private String[] declaration(String owner, String sig) {
        Deque<String> up = new ArrayDeque<>();
        up.add(owner);
        Set<String> seen = new HashSet<>();
        while (!up.isEmpty()) {
            String c = up.poll();
            ClassFile cf = cls(c);
            if (cf == null || !seen.add(c)) {
                continue;
            }
            if (cf.methods.containsKey(sig)) {
                return new String[] {c, sig};
            }
            if (cf.superName != null) {
                up.add(cf.superName);
            }
            up.addAll(cf.interfaces);
        }
        return null;
    }

    private void initialise(String owner, Set<String> initialised, Deque<String[]> todo, Set<String> classes) {
        for (String c = owner; c != null && initialised.add(c); ) {
            ClassFile cf = cls(c);
            if (cf == null) {
                return;
            }
            classes.add(c);
            if (cf.methods.containsKey("<clinit>()V")) {
                todo.add(new String[] {c, "<clinit>()V"});
            }
            c = cf.superName;
        }
    }

    private void instantiate(String owner, Set<String> instantiated, Set<String> initialised, Deque<String[]> todo, Set<String> classes) {
        for (String c = owner; c != null && instantiated.add(c); ) {
            ClassFile cf = cls(c);
            if (cf == null) {
                return;
            }
            initialise(c, initialised, todo, classes);
            for (ClassFile.Method m : cf.methods.values()) {
                todo.add(new String[] {c, m.name() + m.desc()});
            }
            for (String i : cf.interfaces) {   // default methods an instance can run
                ClassFile ic = cls(i);
                if (ic != null) {
                    instantiate(i, instantiated, initialised, todo, classes);
                }
            }
            c = cf.superName;
        }
    }

    /** For a report: how many methods and classes, by package. */
    public static String summary(Reached r) {
        Map<String, Integer> byPkg = new TreeMap<>();
        for (String c : r.classes()) {
            String p = c.substring(OURS.length(), Math.max(OURS.length(), c.lastIndexOf('/')));
            byPkg.merge(p, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> top = new ArrayList<>(byPkg.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        return r.methods().size() + " methods in " + r.classes().size() + " classes " + top.subList(0, Math.min(8, top.size()));
    }
}
