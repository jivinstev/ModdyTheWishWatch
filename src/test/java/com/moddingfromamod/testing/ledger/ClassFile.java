package com.moddingfromamod.testing.ledger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>A JVM class file, read just far enough to say what a method DOES</b> (phase 2): its constant pool, its
 * methods' code, and the bootstrap methods its lambdas name. Pure JDK, no ASM: a check's fingerprint must not depend on a
 * library's version of the format.
 *
 * <p>Two things come out of a method. {@link Method#digest} is a hash of its instructions with every constant-pool
 * operand RESOLVED to what it names (a string literal, a called method, a lambda's body), so a changed literal changes
 * the digest although the code bytes, which hold only pool indices, would not; and the line-number and local-variable
 * tables are left out, so a comment or a blank line above a method changes nothing. {@link Method#refs} is what the
 * method can reach: the methods it calls, the classes it instantiates or names, the classes whose statics it touches.
 */
public final class ClassFile {

    /** What a method's code refers to, for {@link CodeReach}. */
    public record Ref(Kind kind, String owner, String name, String desc) {
    }

    public enum Kind {
        /** invokestatic / invokespecial: this exact declaration, found up the hierarchy. */
        CALL_EXACT,
        /** invokevirtual / invokeinterface: a declaration up the hierarchy, and overrides in instantiated classes. */
        CALL_VIRTUAL,
        /** new, or a constructor handle: the class is instantiated (all its methods can run). */
        NEW,
        /** a class literal: reflection may instantiate it. */
        CLASS_LITERAL,
        /** a static field read or written: the class is initialised. */
        STATIC
    }

    public record Method(String name, String desc, byte[] digest, List<Ref> refs) {
    }

    public final String name;
    public final String superName;
    public final List<String> interfaces;
    public final Map<String, Method> methods;   // key: name + desc

    private final Object[] pool;
    private final int[] tags;
    private final List<int[]> bootstraps = new ArrayList<>();

    private ClassFile(String name, String superName, List<String> interfaces, Map<String, Method> methods, Object[] pool,
            int[] tags) {
        this.name = name;
        this.superName = superName;
        this.interfaces = interfaces;
        this.methods = methods;
        this.pool = pool;
        this.tags = tags;
    }

    public static ClassFile read(byte[] bytes) {
        try {
            return parse(bytes);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("not a class file we can read: " + e, e);
        }
    }

    private static ClassFile parse(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("bad magic");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int n = in.readUnsignedShort();
        Object[] pool = new Object[n];
        int[] tags = new int[n];
        for (int i = 1; i < n; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 3 -> pool[i] = in.readInt();
                case 4 -> pool[i] = in.readFloat();
                case 5 -> {
                    pool[i] = in.readLong();
                    i++;
                }
                case 6 -> {
                    pool[i] = in.readDouble();
                    i++;
                }
                case 7, 8, 16, 19, 20 -> pool[i] = new int[] {in.readUnsignedShort()};
                case 9, 10, 11, 12, 17, 18 -> pool[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                case 15 -> pool[i] = new int[] {in.readUnsignedByte(), in.readUnsignedShort()};
                default -> throw new IOException("constant pool tag " + tag);
            }
        }
        in.readUnsignedShort();
        int thisIdx = in.readUnsignedShort();
        int superIdx = in.readUnsignedShort();
        List<String> ifs = new ArrayList<>();
        for (int i = in.readUnsignedShort(); i > 0; i--) {
            ifs.add(className(pool, in.readUnsignedShort()));
        }
        for (int i = in.readUnsignedShort(); i > 0; i--) {   // fields: nothing in them runs
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.readUnsignedShort();
            skipAttributes(in);
        }
        record RawMethod(String name, String desc, byte[] code, int[] handlers) {
        }
        List<RawMethod> raw = new ArrayList<>();
        for (int i = in.readUnsignedShort(); i > 0; i--) {
            in.readUnsignedShort();
            String mname = (String) pool[in.readUnsignedShort()];
            String mdesc = (String) pool[in.readUnsignedShort()];
            byte[] code = null;
            int[] handlers = new int[0];
            for (int a = in.readUnsignedShort(); a > 0; a--) {
                String an = (String) pool[in.readUnsignedShort()];
                int len = in.readInt();
                if (an.equals("Code")) {
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                    code = new byte[in.readInt()];
                    in.readFully(code);
                    int ex = in.readUnsignedShort();
                    handlers = new int[ex * 4];
                    for (int e = 0; e < handlers.length; e++) {
                        handlers[e] = in.readUnsignedShort();
                    }
                    skipAttributes(in);   // LineNumberTable, LocalVariableTable, StackMapTable: not what it does
                } else {
                    in.skipBytes(len);
                }
            }
            raw.add(new RawMethod(mname, mdesc, code, handlers));
        }
        ClassFile cf = new ClassFile(className(pool, thisIdx), superIdx == 0 ? null : className(pool, superIdx), List.copyOf(ifs),
                new LinkedHashMap<>(), pool, tags);
        for (int a = in.readUnsignedShort(); a > 0; a--) {
            String an = (String) pool[in.readUnsignedShort()];
            int len = in.readInt();
            if (an.equals("BootstrapMethods")) {
                for (int b = in.readUnsignedShort(); b > 0; b--) {
                    int ref = in.readUnsignedShort();
                    int argc = in.readUnsignedShort();
                    int[] bsm = new int[argc + 1];
                    bsm[0] = ref;
                    for (int x = 1; x <= argc; x++) {
                        bsm[x] = in.readUnsignedShort();
                    }
                    cf.bootstraps.add(bsm);
                }
            } else {
                in.skipBytes(len);
            }
        }
        for (RawMethod m : raw) {
            cf.methods.put(m.name() + m.desc(), cf.method(m.name(), m.desc(), m.code(), m.handlers()));
        }
        return cf;
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        for (int a = in.readUnsignedShort(); a > 0; a--) {
            in.readUnsignedShort();
            in.skipBytes(in.readInt());
        }
    }

    private static String className(Object[] pool, int idx) {
        return (String) pool[((int[]) pool[idx])[0]];
    }

    private String utf(int idx) {
        return (String) pool[idx];
    }

    /** What a constant-pool entry names, as text: the operand a digest records instead of the index. */
    String resolve(int idx) {
        if (idx <= 0 || idx >= pool.length) {
            return "#" + idx;
        }
        Object v = pool[idx];
        int[] r = v instanceof int[] a ? a : null;
        return switch (tags[idx]) {
            case 1 -> "U:" + v;
            case 3, 4, 5, 6 -> "N:" + v;
            case 7 -> "C:" + utf(r[0]);
            case 8 -> "S:" + utf(r[0]);
            case 9, 10, 11 -> "M:" + className(pool, r[0]) + "." + resolveNat(r[1]);
            case 12 -> resolveNat(idx);
            case 15 -> "H" + r[0] + ":" + resolve(r[1]);
            case 16 -> "T:" + utf(r[0]);
            case 17, 18 -> "D:" + resolveBootstrap(r[0]) + ":" + resolveNat(r[1]);
            default -> "?" + tags[idx];
        };
    }

    private String resolveNat(int idx) {
        int[] r = (int[]) pool[idx];
        return utf(r[0]) + utf(r[1]);
    }

    private String resolveBootstrap(int b) {
        if (b >= bootstraps.size()) {
            return "bsm?" + b;
        }
        StringBuilder s = new StringBuilder("bsm(");
        for (int x : bootstraps.get(b)) {
            s.append(resolve(x)).append(';');
        }
        return s.append(')').toString();
    }

    private static final int[] LENGTH = new int[256];

    static {
        java.util.Arrays.fill(LENGTH, 1);
        LENGTH[0x10] = 2;
        LENGTH[0x11] = 3;
        LENGTH[0x12] = 2;
        LENGTH[0x13] = 3;
        LENGTH[0x14] = 3;
        for (int op = 0x15; op <= 0x19; op++) {
            LENGTH[op] = 2;
        }
        for (int op = 0x36; op <= 0x3a; op++) {
            LENGTH[op] = 2;
        }
        LENGTH[0x84] = 3;
        for (int op = 0x99; op <= 0xa8; op++) {
            LENGTH[op] = 3;
        }
        LENGTH[0xa9] = 2;
        for (int op = 0xb2; op <= 0xb8; op++) {
            LENGTH[op] = 3;
        }
        LENGTH[0xb9] = 5;
        LENGTH[0xba] = 5;
        LENGTH[0xbb] = 3;
        LENGTH[0xbc] = 2;
        LENGTH[0xbd] = 3;
        LENGTH[0xc0] = 3;
        LENGTH[0xc1] = 3;
        LENGTH[0xc5] = 4;
        LENGTH[0xc6] = 3;
        LENGTH[0xc7] = 3;
        LENGTH[0xc8] = 5;
        LENGTH[0xc9] = 5;
    }

    private static int u2(byte[] c, int i) {
        return ((c[i] & 0xff) << 8) | (c[i + 1] & 0xff);
    }

    private static int s4(byte[] c, int i) {
        return ((c[i] & 0xff) << 24) | ((c[i + 1] & 0xff) << 16) | ((c[i + 2] & 0xff) << 8) | (c[i + 3] & 0xff);
    }

    private Method method(String mname, String mdesc, byte[] code, int[] handlers) {
        MessageDigest md = sha();
        List<Ref> refs = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        if (code != null) {
            int pc = 0;
            while (pc < code.length) {
                int op = code[pc] & 0xff;
                int len;
                text.append(op);
                switch (op) {
                    case 0xaa -> {   // tableswitch
                        int p = (pc + 4) & ~3;
                        int lo = s4(code, p + 4);
                        int hi = s4(code, p + 8);
                        len = p + 12 + (hi - lo + 1) * 4 - pc;
                        appendRaw(text, code, pc + 1, len - 1);
                    }
                    case 0xab -> {   // lookupswitch
                        int p = (pc + 4) & ~3;
                        int pairs = s4(code, p + 4);
                        len = p + 8 + pairs * 8 - pc;
                        appendRaw(text, code, pc + 1, len - 1);
                    }
                    case 0xc4 -> {   // wide
                        len = (code[pc + 1] & 0xff) == 0x84 ? 6 : 4;
                        appendRaw(text, code, pc + 1, len - 1);
                    }
                    case 0x12 -> {   // ldc: one-byte pool index
                        len = 2;
                        int idx = code[pc + 1] & 0xff;
                        text.append('(').append(resolve(idx)).append(')');
                        literal(idx, refs);
                    }
                    case 0x13, 0x14, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xbb, 0xbd, 0xc0, 0xc1, 0xc5 -> {
                        len = LENGTH[op];
                        int idx = u2(code, pc + 1);
                        text.append('(').append(resolve(idx)).append(')');
                        if (op == 0xc5) {
                            text.append(code[pc + 3]);
                        }
                        refer(op, idx, refs);
                    }
                    default -> {
                        len = LENGTH[op];
                        appendRaw(text, code, pc + 1, len - 1);
                    }
                }
                text.append(' ');
                pc += len;
            }
            for (int e = 0; e + 3 < handlers.length; e += 4) {
                text.append("try").append(handlers[e]).append(',').append(handlers[e + 1]).append(',').append(handlers[e + 2])
                        .append(',').append(handlers[e + 3] == 0 ? "any" : resolve(handlers[e + 3])).append(' ');
            }
        } else {
            text.append("nocode");
        }
        md.update((mname + mdesc + "|").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        md.update(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Method(mname, mdesc, md.digest(), List.copyOf(refs));
    }

    private static void appendRaw(StringBuilder text, byte[] code, int from, int n) {
        for (int i = 0; i < n; i++) {
            text.append(',').append(code[from + i]);
        }
    }

    private void literal(int idx, List<Ref> refs) {
        if (tags[idx] == 7) {
            refs.add(new Ref(Kind.CLASS_LITERAL, className(pool, idx), null, null));
        } else if (tags[idx] == 15) {
            handle(idx, refs);
        } else if (tags[idx] == 17) {
            bootstrapRefs(((int[]) pool[idx])[0], refs);
        }
    }

    private void refer(int op, int idx, List<Ref> refs) {
        switch (op) {
            case 0x13, 0x14 -> literal(idx, refs);
            case 0xb2, 0xb3 -> refs.add(new Ref(Kind.STATIC, className(pool, ((int[]) pool[idx])[0]), null, null));
            case 0xb7, 0xb8 -> member(Kind.CALL_EXACT, idx, refs);
            case 0xb6, 0xb9 -> member(Kind.CALL_VIRTUAL, idx, refs);
            case 0xba -> bootstrapRefs(((int[]) pool[idx])[0], refs);
            case 0xbb -> refs.add(new Ref(Kind.NEW, className(pool, idx), null, null));
            default -> {
            }
        }
    }

    private void member(Kind kind, int idx, List<Ref> refs) {
        int[] r = (int[]) pool[idx];
        int[] nat = (int[]) pool[r[1]];
        refs.add(new Ref(kind, className(pool, r[0]), utf(nat[0]), utf(nat[1])));
    }

    private void handle(int idx, List<Ref> refs) {
        int[] h = (int[]) pool[idx];
        int refKind = h[0];
        int target = h[1];
        if (tags[target] == 9) {
            refs.add(new Ref(Kind.STATIC, className(pool, ((int[]) pool[target])[0]), null, null));
            return;
        }
        // 5 virtual, 9 interface: dispatched; 6 static, 7 special; 8 newInvokeSpecial: a constructor reference
        member(refKind == 5 || refKind == 9 ? Kind.CALL_VIRTUAL : Kind.CALL_EXACT, target, refs);
        if (refKind == 8) {
            refs.add(new Ref(Kind.NEW, className(pool, ((int[]) pool[target])[0]), null, null));
        }
    }

    private void bootstrapRefs(int b, List<Ref> refs) {
        if (b >= bootstraps.size()) {
            return;
        }
        for (int x : bootstraps.get(b)) {
            literal(x, refs);
        }
    }

    static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
