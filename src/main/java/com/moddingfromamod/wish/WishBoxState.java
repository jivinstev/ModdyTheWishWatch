package com.moddingfromamod.wish;

/**
 * Whose words are in the wish box right now — the child's, or Moddy's.
 *
 * <p><b>Why this needs to exist at all.</b> One text box does two jobs on the wish screen: it is
 * where a wish is typed, and it is where the reason for pressing 👎 is typed. That was harmless
 * until Moddy started SEEDING the box with a suggested improvement — at which point pressing 👎 on a
 * wish would submit Moddy's own suggestion as the child's complaint about it. Words in his mouth at
 * the exact moment he is telling us we got it wrong.
 *
 * <p>So the box tracks provenance, and the rule is simply: <b>a rejection never carries Moddy's own
 * words.</b> The moment the child types, edits or clears, the text becomes his and behaves normally
 * again.
 *
 * <p>Deliberately holds no text — the {@code EditBox} remains the single source of truth for that,
 * and a second copy would only drift. Pure and Minecraft-free so this small, easily-broken rule is
 * pinned by tests rather than by someone remembering it.
 */
public final class WishBoxState {

    private boolean fromModdy;

    /** Moddy just put a suggestion in the box. */
    public void seeded() {
        this.fromModdy = true;
    }

    /** The child typed, pasted or backspaced — from here the text is his. */
    public void edited() {
        this.fromModdy = false;
    }

    /** The box was emptied, or its contents were sent. Either way there is no offer left standing. */
    public void cleared() {
        this.fromModdy = false;
    }

    /** True while the box still holds words Moddy wrote rather than the child. */
    public boolean holdsModdysWords() {
        return fromModdy;
    }

    /**
     * What the box should contain before a freshly typed character is inserted.
     *
     * <p><b>The first keystroke over a suggestion REPLACES it.</b> The seeding is offered on the
     * promise that he "can still type over it or bin it"; binning it worked, and typing over it
     * appended instead — his sentence was glued onto Moddy's with no space, and the joined result
     * was sent as one wish and rendered back on his card as his own words:
     *
     * <pre>"make Spikor's fire thinner so I can see himbuild me a lighthouse..."</pre>
     *
     * <p>Same fault this class was written for, one step further along: the 👎 button was taught not
     * to put Moddy's words in his mouth, and the text box was not.
     *
     * @param current what is in the box now
     * @return what should be in the box as he starts typing — his own text, or nothing
     */
    public String beforeTyping(String current) {
        return fromModdy ? "" : (current == null ? "" : current);
    }

    /**
     * What the 👎 button should send as the reason: the child's own words, or nothing at all.
     *
     * <p>Empty is a perfectly good rejection — the server picks the wish to undo by recency, not
     * from this text (see {@code WishRejection}), so a blank reason still undoes the right thing. It
     * just doesn't pretend the child said something he didn't.
     */
    public String rejectionReason(String boxText) {
        return fromModdy || boxText == null ? "" : boxText;
    }
}
