# Conventions: naming, registration and style

Read this before adding an identifier, a registry entry, an item, a translation key or a new file, and when
looking something up in Fabric or Minecraft sources.

## Naming

| Kind | Convention | Example |
|---|---|---|
| Mod id | lowercase, no separators | `digicube` |
| Registry holder class | `DC` + plural noun | `DCItems`, `DCBlocks`, `DCEntityTypes`, `DCSounds` |
| Registry field | `SCREAMING_SNAKE_CASE`, matches its id | `DIGIVICE` for `digicube:digivice` |
| `Identifier` path | `snake_case`, English | `training_dummy` |
| Java package | `com.digicube.<feature>` | `com.digicube.digimon` |
| Fabric package | `com.digicube.fabric.<feature>` | `com.digicube.fabric.client` |
| Mixin class | `Mixin` + target class name | `MixinMinecraft` |
| Mixin injected member | prefixed `digicube$` | `digicube$onClientInit` |
| Translation key | `<type>.digicube.<path>` | `item.digicube.digivice` |
| Species translation key | `digimon.digicube.<name>` | `digimon.digicube.agumon` |

**Always build identifiers with `Constants.id("thing")`.** Never write
`Identifier.fromNamespaceAndPath("digicube", ...)` inline, and never a bare string
literal `"digicube:thing"`.

Digimon names use their **Japanese romanisation** as the id (`agumon`, `greymon`,
`wargreymon`). English dub names, where they differ, belong in `en_us.json` only.

## Registration

Registration happens in the `DC*` classes under
`common/src/main/java/com/digicube/registry/`. Fields are `static final` and register
themselves in the static initialiser; each class exposes an `init()` that the loader
entry point calls to force class loading.

Since Minecraft 1.21.2 an `Item` must know its own id **before** construction, hence the
factory / `setId` pattern:

```java
private static ResourceKey<Item> key(String path) {
    return ResourceKey.create(Registries.ITEM, Constants.id(path));
}

private static Item register(ResourceKey<Item> key, Function<Item.Properties, Item> factory, Item.Properties properties) {
    Item item = factory.apply(properties.setId(key));
    return Registry.register(BuiltInRegistries.ITEM, key, item);
}
```

Copy this shape for blocks (`Registries.BLOCK` / `BuiltInRegistries.BLOCK`), entity types,
sounds and so on. Do **not** reach for Fabric's registry helpers — they would drag a loader
import into `common/`.

### Checklist: adding an item

Miss a step and it shows up in game as a black-and-purple cube named `item.digicube.foo`.

- [ ] `ResourceKey` + `Item` field in `DCItems`
- [ ] `common/src/main/resources/assets/digicube/items/foo.json` — client item definition
- [ ] `common/src/main/resources/assets/digicube/models/item/foo.json` — the model
- [ ] `common/src/main/resources/assets/digicube/textures/item/foo.png` — 16x16 PNG
- [ ] `item.digicube.foo` in `assets/digicube/lang/en_us.json`
- [ ] Add player-facing items to the DigiCube Creative tab's ordered `displayItems`
      list and an appropriate vanilla category in `fabric/.../registry/DCCreativeTabs.java`

Verify in game with `/give @s digicube:foo`.

The dedicated **DigiCube** tab is the home for all player-facing mod items, with the
Digivice as its icon and first item. The Digivice also appears in **Tools & Utilities**,
after the compass, and in Creative search. Retain appropriate vanilla-category
entries as the collection grows. The Fabric builder and events live in
`fabric/.../registry/DCCreativeTabs.java`; initialize it after `DCItems` on both sides.
Fabric tab APIs belong in `fabric/`, not `common/`.

## Formatting

JSON files use **2-space** indentation; Java uses **4 spaces**.

## Where to look things up

- Fabric docs, with the selector set to **26.2**: <https://docs.fabricmc.net/develop/>
- Fabric API source: <https://github.com/FabricMC/fabric>
- NeoForge docs: <https://docs.neoforged.net/>
- The MultiLoader template this layout follows: <https://github.com/jaredlll08/MultiLoader-Template>
- Cobblemon, the reference for a large data-driven creature mod: <https://gitlab.com/cable-mc/cobblemon>
- Blockbench, for models and animations: <https://www.blockbench.net/>
