package com.moddingfromamod.reference;

/**
 * <b>: check and cache the briefs of the most-wished characters, ahead of any wish.</b>
 *
 * <p>Runs {@link WishBriefs#warm} for each name: the real lookup, the vision description, the injection
 * check, the colour agreement and the child-safety grade; a look that passed all of them is cached, so a
 * wish for that character pays no lookup, no description and no grade. Model calls happen only when the
 * host allows them ({@code -Dmoddingfromamod.agent=on} from a shell). One JSON line per name.
 *
 * <pre>java -Dmoddingfromamod.agent=on -cp … com.moddingfromamod.reference.BriefWarm descriptive Puffo Plumo</pre>
 */
public final class BriefWarm {

    private BriefWarm() {
    }

    public static void main(String[] args) {
        WishBrief.Mode mode = WishBrief.Mode.parse(args.length > 0 ? args[0] : "descriptive");
        for (int i = 1; i < args.length; i++) {
            WishBriefs.Result r = WishBriefs.warm(args[i], mode);
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("name", args[i]);
            o.addProperty("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
            o.addProperty("outcome", r.outcome());
            o.addProperty("cached", r.ok() && r.why().isEmpty());
            o.addProperty("why", r.why());
            o.addProperty("ms", r.readyMs());
            long[] st = WishBriefs.STEPS.getOrDefault(args[i], new long[5]);
            o.addProperty("lookupMs", st[0]);
            o.addProperty("lookMs", st[1]);
            o.addProperty("picturesMs", st[2]);
            o.addProperty("describerMs", st[3]);
            o.addProperty("checksMs", st[4]);
            o.addProperty("look", r.look());
            System.out.println(o);
        }
        System.exit(0);
    }
}
