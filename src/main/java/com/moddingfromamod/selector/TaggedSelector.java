package com.moddingfromamod.selector;

import java.util.List;

/** The positive tags (and text) a parsed selector carries ({@code mixin/SelectorParserTagsMixin}). */
public interface TaggedSelector {

    List<String> moddingfromamod$tags();

    String moddingfromamod$text();

    void moddingfromamod$setTags(List<String> tags, String text);
}
