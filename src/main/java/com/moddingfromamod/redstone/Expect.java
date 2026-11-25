package com.moddingfromamod.redstone;

/**
 * What one block must be at rest (control off) and when fired (control on). A value is a block id, optionally with
 * the properties that must match, e.g. {@code minecraft:sticky_piston[extended=true]}; {@code "*"} means "anything".
 */
public record Expect(RsPos pos, String rest, String fired, String what) {
}
