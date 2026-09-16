# Dont reload my fucking resource packs

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
- It does not cache anything Minecraft wasn't already caching. No extra folder,
  no growing directory to clean up.
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

## Side effect worth knowing

After you leave a server its pack stays applied while you're in the menu. It is
cleared the moment you join somewhere with a different pack set, or open a
single-player world. If you find that strange, that's the trade: holding the
pack for a few seconds in the menu is what removes the reload from every
rejoin.
