package com.moddingfromamod.reference;

import com.google.gson.JsonObject;

/**
 * <b>: the brief a named character's wish would get, printed, with no game running.</b>
 *
 * <p>For evals and for a person reviewing what a worker would read: for each name, the cached reference
 * (never a live lookup), the structured look (a), the descriptive look (b, a real vision call), and
 * what the injection check says about each. One JSON line per name and kind.
 *
 * <pre>./gradlew -q compileJava classpathFile
 * java -cp "build/classes/java/main:build/resources/main:$(cat build/runtime-classpath.txt)" \
 *     com.moddingfromamod.reference.BriefPreview Puffo Plumo</pre>
 */
public final class BriefPreview {

    private BriefPreview() {
    }

    public static void main(String[] names) {
        boolean describe = !Boolean.getBoolean("brief.structuredOnly");
        for (String name : names) {
            ReferenceSpec spec = ReferenceResearcher.cachedOnly(name);
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("reference", spec != null && !spec.isEmpty());
            if (spec != null && !spec.isEmpty()) {
                String a = WishBrief.structuredLook(spec);
                o.addProperty("structured", a);
                o.addProperty("structuredInjection", WishBrief.injection(a).orElse(""));
                if (describe) {
                    long t0 = System.currentTimeMillis();
                    String b = WishBrief.veil(BriefLooks.describe(name, spec, SubjectKind.CHARACTER), name)
                            .replace(WishBrief.TOKEN, "it");
                    o.addProperty("descriptive", b);
                    o.addProperty("descriptiveMs", System.currentTimeMillis() - t0);
                    o.addProperty("descriptiveInjection", WishBrief.injection(b).orElse(""));
                    o.addProperty("descriptiveAgrees", WishBrief.agreesWith(b, spec));
                }
            }
            System.out.println(o);
        }
        System.exit(0);
    }
}
