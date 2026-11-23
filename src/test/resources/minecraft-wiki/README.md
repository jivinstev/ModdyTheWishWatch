# minecraft.wiki infobox fixtures

Real MediaWiki API answers from `https://minecraft.wiki/api.php`, fetched 2026-10-03 with

    action=query&prop=revisions&rvprop=content|ids&rvslots=main&titles=<T>&redirects=1&format=json&formatversion=2

— the exact request `reference/MinecraftWikiPerson` sends. Byte for byte, except `Creeper.json`,
whose page text is cut to its first 2,500 characters (a mob page, here only as "a page that is not
a person"; the full page is 40 KB). `MinecraftWikiPersonTest` reads them as its table of real
infobox shapes: a plain `minecraft_name` (BigB, Smajor, Shubble), an inline one-line infobox
(Tubbo), link markup with two names (Camman18), `minecraft_names` with `({{JE}})`/`({{BE}})`
markers (LDShadowLady), a comma list of `mine.ly` links (Technoblade), an Xbox gamertag and
nothing else (Stampy), a redirect (Joseph_Garrett → Stampy), a person with no name field (Grian),
no page at all (SethBling) and a page that is not a person (Creeper). Refetch with the same
request when a page changes; a test that pins a revision id will say which.
