import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;

/**
 * The coverage map's analysis step (docs/GATE_B.md, "The coverage map"): the JaCoCo execution data one mapping job
 * dumped, one file per unit and segment ({@code testing/gt/CoverageMap}: {@code <unit>.<n>.exec}), turned into the
 * METHODS each unit executed, plus the table of every method's source file and lines.
 *
 * <p>Run by {@code tools/gate/covmap.py} with the JaCoCo core jars on the class path, as a single-file program:
 * {@code java -cp <jars> tools/gate/CovmapAnalyze.java <classes dir> <exec dir> <out.json>}. A tool, never part of the
 * mod: it is not under src/ and nothing compiles it into the jar.
 *
 * <p>A method's id is {@code package/Class#name} with what a recompile renumbers folded away, so a map made at one
 * commit still names the same methods at the next: a lambda counts as the method that holds it
 * ({@code lambda$tick$3} is {@code tick}; in a static initialiser {@code <clinit>}, in a constructor {@code <init>}), an
 * anonymous class is {@code Outer$anon}, and overloads share their name. Folding only ever makes a method bigger, so a
 * change selects more, never less.
 */
public final class CovmapAnalyze {

    private CovmapAnalyze() {
    }

    public static void main(String[] args) throws IOException {
        Path classes = Path.of(args[0]);
        Path execDir = Path.of(args[1]);
        Path out = Path.of(args[2]);

        Map<String, byte[]> classFiles = new HashMap<>();   // vm name (com/x/Foo) -> bytes
        try (Stream<Path> s = Files.walk(classes)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".class"))::iterator) {
                String rel = classes.relativize(p).toString().replace('\\', '/');
                classFiles.put(rel.substring(0, rel.length() - ".class".length()), Files.readAllBytes(p));
            }
        }

        // the method table: every method of every class, with or without execution data
        Map<String, String> fileOf = new TreeMap<>();
        Map<String, TreeSet<String>> linesOf = new TreeMap<>();
        CoverageBuilder all = new CoverageBuilder();
        Analyzer whole = new Analyzer(new ExecutionDataStore(), all);
        for (Map.Entry<String, byte[]> e : classFiles.entrySet()) {
            whole.analyzeClass(e.getValue(), e.getKey());
        }
        for (IClassCoverage c : all.getClasses()) {
            String src = c.getSourceFileName() == null ? null : c.getPackageName() + "/" + c.getSourceFileName();
            for (IMethodCoverage m : c.getMethods()) {
                String id = id(c.getName(), m.getName());
                if (src != null) {
                    fileOf.putIfAbsent(id, src);
                }
                if (m.getFirstLine() > 0) {
                    linesOf.computeIfAbsent(id, k -> new TreeSet<>()).add(m.getFirstLine() + "-" + m.getLastLine());
                }
            }
        }

        // the units: every exec file of a unit (its segments) together, and the methods with a covered instruction
        Map<String, List<Path>> segments = new TreeMap<>();
        try (Stream<Path> s = Files.list(execDir)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".exec"))::iterator) {
                String n = p.getFileName().toString();
                String stem = n.substring(0, n.length() - ".exec".length());
                String unit = stem.substring(0, stem.lastIndexOf('.'));
                segments.computeIfAbsent(unit, k -> new ArrayList<>()).add(p);
            }
        }
        Map<String, TreeSet<String>> units = new TreeMap<>();
        for (Map.Entry<String, List<Path>> e : segments.entrySet()) {
            ExecFileLoader loader = new ExecFileLoader();
            for (Path p : e.getValue()) {
                try (InputStream in = Files.newInputStream(p)) {
                    loader.load(in);
                }
            }
            ExecutionDataStore store = loader.getExecutionDataStore();
            CoverageBuilder cb = new CoverageBuilder();
            Analyzer a = new Analyzer(store, cb);
            for (ExecutionData d : store.getContents()) {
                byte[] bytes = classFiles.get(d.getName());
                if (bytes != null && d.hasHits()) {
                    a.analyzeClass(bytes, d.getName());
                }
            }
            TreeSet<String> hit = new TreeSet<>();
            for (IClassCoverage c : cb.getClasses()) {
                for (IMethodCoverage m : c.getMethods()) {
                    if (m.getInstructionCounter().getCoveredCount() > 0) {
                        hit.add(id(c.getName(), m.getName()));
                    }
                }
            }
            units.put(e.getKey(), hit);
        }

        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("{\"methods\": {");
            boolean first = true;
            for (String id : new TreeSet<>(union(fileOf.keySet(), linesOf.keySet()))) {
                w.write((first ? "\n" : ",\n") + q(id) + ": {\"file\": " + q(fileOf.getOrDefault(id, "")) + ", \"lines\": [");
                first = false;
                boolean f2 = true;
                for (String r : linesOf.getOrDefault(id, new TreeSet<>())) {
                    String[] ab = r.split("-");
                    w.write((f2 ? "" : ", ") + "[" + ab[0] + ", " + ab[1] + "]");
                    f2 = false;
                }
                w.write("]}");
            }
            w.write("},\n\"units\": {");
            first = true;
            for (Map.Entry<String, TreeSet<String>> e : units.entrySet()) {
                w.write((first ? "\n" : ",\n") + q(e.getKey()) + ": [");
                first = false;
                boolean f2 = true;
                for (String id : e.getValue()) {
                    w.write((f2 ? "" : ", ") + q(id));
                    f2 = false;
                }
                w.write("]");
            }
            w.write("}}\n");
        }
        System.out.println("covmap: " + units.size() + " units, " + linesOf.size() + " methods, from " + classFiles.size()
                + " classes");
    }

    private static TreeSet<String> union(java.util.Set<String> a, java.util.Set<String> b) {
        TreeSet<String> t = new TreeSet<>(a);
        t.addAll(b);
        return t;
    }

    /** {@code com/x/Foo$1}, {@code lambda$tick$3} -> {@code com/x/Foo$anon#tick}. */
    static String id(String vmClass, String method) {
        String cls = vmClass.replaceAll("\\$[0-9]+(?=\\$|$)", "\\$anon");
        String m = method;
        if (m.startsWith("lambda$")) {
            String[] parts = m.split("\\$");
            m = parts.length > 1 ? parts[1] : m;
            if (m.equals("static")) {
                m = "<clinit>";
            } else if (m.equals("new")) {
                m = "<init>";
            }
        }
        return cls + "#" + m;
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                b.append('\\');
            }
            b.append(c);
        }
        return b.append('"').toString();
    }
}
