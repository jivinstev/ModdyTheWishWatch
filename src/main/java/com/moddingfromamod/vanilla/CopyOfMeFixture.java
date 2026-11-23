package com.moddingfromamod.vanilla;

/**
 * <b>The creature: a "copy of me" as the live server saw it.</b> Eyes and mouth are children
 * of {@code head}; the big hair box over the top and back of the head is NOT (a root), which is the
 * shape that turned its face to look at the child and left the hair behind. A yellow button on the
 * chest shows which way the BODY faces.
 *
 * <p>One copy, shared by the Gate C film ({@code vanillahair}), the traffic probe
 * ({@code vanillaheadperf}) and the stock-client stage ({@code lite/StockHairStage}), so the three
 * cannot drift apart. Kept out of {@code client.test} because a dedicated server loads the stage.
 */
public final class CopyOfMeFixture {

    private CopyOfMeFixture() {
    }

    public static final String JSON = """
            [{"name":"legL","from":[-4,0,-2],"to":[0,12,2],"colour":"#2B3F8C"},
             {"name":"legR","from":[0,0,-2],"to":[4,12,2],"colour":"#2B3F8C"},
             {"name":"body","from":[-4,12,-2],"to":[4,24,2],"colour":"#2B3F8C"},
             {"name":"button","parent":"body","from":[-2,17,-3],"to":[2,21,-2],"colour":"#FFD800"},
             {"name":"armL","from":[-8,12,-2],"to":[-4,24,2],"colour":"#E8A08C"},
             {"name":"armR","from":[4,12,-2],"to":[8,24,2],"colour":"#E8A08C"},
             {"name":"head","from":[-4,24,-4],"to":[4,32,4],"colour":"#E8A08C"},
             {"name":"eyeL","parent":"head","from":[-3,28,-5],"to":[-1,30,-4],"colour":"#101010"},
             {"name":"eyeR","parent":"head","from":[1,28,-5],"to":[3,30,-4],"colour":"#101010"},
             {"name":"mouth","parent":"head","from":[-2,25,-5],"to":[2,27,-4],"colour":"#D02020"},
             {"name":"hair","from":[-5,30,-5],"to":[5,34,5],"colour":"#6B3A1E"},
             {"name":"hairBack","from":[-5,25,1],"to":[5,31,5],"colour":"#6B3A1E"}]""";
}
