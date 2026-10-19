# Dont reload my fucking resource packs

## AI disclosure

LLMs were used to build this project. I want to learn how everything here works and rewrite these mods without AI some day, but that isn't something I can do right now.

That said, I made these mods for myself first. If one of them were pointless or clunky to use, I wouldn't be using it either.

If you'd like to help make these mods better, go for it.

---

Skips the resource reload when a server pushes packs you already have.

## The problem

Minecraft caches a server's resource pack under the **UUID the server sends**,
not under the pack's contents. Plenty of servers make up a fresh UUID on every
connection, and that defeats the cache completely. Every time you rejoin, the
same bytes are downloaded again and the game sits through a full resource
reload — the black screen, the progress bar, the several seconds where nothing
responds.

Nothing actually changed. The download log on the machine this was written on
had 985 entries, and 24 packs appeared four times each: same content hash every
single time, a different UUID every single time. The client had the file. It
just had no way to know it was the same file.

Leaving a server makes it worse. The game drops the server's packs on
disconnect, which means even a cache hit would have nothing to hit — the next
join starts from nothing regardless.

## What this does

It ignores the UUID and looks at the **content hash** instead. Minecraft
already computes that hash and already uses it as the downloaded file's name,
so there is nothing new to compute and nothing new to store.

- Same packs as the ones already applied → the reload is skipped.
- Anything genuinely different — a pack added, removed, reordered, or its
  content changed → the reload happens exactly as it normally would.

It also **keeps server packs loaded when you disconnect**. Dropping them there
is what makes the next join expensive; holding them is what turns a rejoin into
a no-op.

## What it does not do

- It does not stop downloads. If the server sends a pack, the game fetches it
  the way it always did. This only changes *when the game decides it has to
  reload*.
- It does not cache anything Minecraft wasn't already caching. The only thing
  it writes is `config/drmfrp-last-packs.txt`, a few lines naming the last
  server's already-downloaded packs.
- It does not lie to the server. The pack-accepted response is still sent, so
  servers with `require-resource-pack=true` behave normally.
- It does not touch your own resource packs, only the ones a server pushes.

## Safety

The comparison is exact and order-sensitive. A reordered pack list counts as a
change, because order decides which pack wins a conflict. If the pack list
can't be read for any reason the mod steps aside and lets vanilla reload — it
will never be the reason a pack fails to load.

Every decision is logged, so you can see which path was taken:

```
[drmfrp] same 1 pack(s) already applied, skipping reload
[drmfrp] pack set changed (1 -> 2), reloading
```

## Across restarts

The last server's packs are loaded at game startup, as part of the load the
game does anyway. Launching and then joining that server used to mean two full
reloads; now the join finds the packs already applied and skips its reload.

A pack is only remembered after it has loaded successfully. If a remembered
pack ever breaks the startup load, the mod drops it, deletes the list and lets
the game recover — it will not fail the same way on the next launch. Packs the
game has since cleaned out of its download cache are simply left out.

## Side effect worth knowing

The last server's pack stays applied until you join somewhere that sends a
different pack set: in the menu, in single-player, and on a server that sends
no pack at all. That is the trade — keeping it is what removes the reload from
every rejoin and from every launch. A single-player world's own
`resources.zip` is never treated as a match, since every world's is called the
same thing.
