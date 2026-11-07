# Iris shader pack support: design and roadmap

This fork's goal: keep Nvidium's mesh-shader terrain renderer running while an
Iris shader pack is active. Each pack's terrain shaders are translated
automatically into mesh-shader programs, so packs work without hand edits.

- Base: upstream `MCRcortex/nvidium`, branch `26.3` (commit `27ceb72`).
- Minecraft 26.3, Sodium 0.9.2, Iris 1.11.6 (the Iris source was read at
  `IrisShaders/Iris` branch `26.3`, commit `671a5c8`).
- Work branch: `iris-compat`.

Status: **M1 tested in game** (Nvidium stays on with a pack and places terrain correctly; terrain
had no pack shading, as expected). **M2 implemented, not yet tested in game**: compiles, and
16 unit tests pass (shader rewriting, mesh shader assembly and syntax, and the mixin targets
checked against the Iris jar).

### Milestone log

**M1, keep Nvidium on with a pack.** Option **"Iris shader pack support (experimental)"**
(`iris_support` in `config/nvidium-config.json`, default off). `TerrainVertexLayout` picks the
vertex layout once per renderer; with a pack active it is Iris's extended format, and chunk
repacking, the geometry arena and the shaders all follow it. Nvidium draws nothing in Iris's
shadow pass. Tested with BlackHole: terrain depth and shape were right, colour was missing
because Nvidium's own shader doesn't write the pack's G-buffers.

**M2, opaque terrain through the pack's shaders.** How it works (code in `me.cortex.nvidium.iris`):

1. `MixinIrisRenderingPipeline`: after Iris creates the pack's program for Sodium's
   `SODIUM_TERRAIN_CUTOUT`, it asks Iris to create it twice more (Nvidium's primary and
   temporal passes) with the same framebuffers, alpha test and uniforms.
2. `MixinShaderCreator`: during those two calls, Iris's final `link` step is replaced by
   `MeshProgramLinker`, which receives Iris's fully patched sources.
3. `MeshShaderTransformer` (glsl-transformer, the parser Iris uses) rewrites the patched
   vertex shader into a function: inputs become globals, outputs go to `_nv_vs_*` globals
   with generated per-vertex mesh outputs, `iris_SodiumPushConstants` becomes globals,
   `gl_Position`/`gl_VertexID` are redirected, `main` is renamed, and global initializers
   (Iris's `iris_Entity`, `iris_MidTex`, ...) are moved into a function re-run per vertex.
4. `MeshShaderAssembler` fills `shaders/terrain/iris/mesh.glsl`: it loads each vertex from
   Nvidium's terrain buffer into the pack's inputs, using the field offsets from Iris's actual
   vertex format, sets `iris_RegionOffset` to the section's camera-relative position, runs the
   pack function for each vertex and emits the triangles like `terrain/mesh.glsl`. It
   deliberately does not enable `GL_NV_gpu_shader5`, which makes common pack code like
   `clamp(x, 0, 1.0)` ambiguous.
5. The program (Nvidium's task shader + generated mesh shader + the pack's fragment shader,
   unchanged) is wrapped by Iris in a normal `ExtendedShader`, which sets the pack's uniforms,
   samplers, G-buffer framebuffer and blending. Nvidium's `SceneData` block moves to uniform
   buffer slot 8, because Iris puts the pack's `u_Globals` on slot 0.
6. `IrisTerrainPass` draws with it: it fills a `u_Globals` block like Sodium's, binds the block
   atlas (nearest filtering with mipmaps, as Iris does), the lightmap, and a one-entry
   `u_SectionTimeInfo` that disables chunk fade-in.

If building fails (for example a pack whose terrain program uses geometry or tessellation
shaders), Nvidium logs the reason and keeps drawing terrain with its own shaders. If the pack's
per-vertex outputs exceed the mesh shader output limit, the linker retries with 16 and then 8
threads per workgroup. Sources of a program that fails are written to `nvidium-iris-debug/` in
the game folder; start the game with `-Dnvidium.isDebug=TRUE` to write them for every program.

### Known limitations after M2

- Solid and cutout terrain share the pack's **cutout** program (Nvidium draws them in one
  pass). Packs whose `gbuffers_terrain_solid` and `gbuffers_terrain_cutout` differ will show
  the cutout version for solid blocks.
- **Water and other translucent blocks** still use Nvidium's own shader (M3).
- **No terrain shadows** (M4).
- Chunk fade-in is disabled (`mc_chunkFade` is always 1).
- Region transforms set through `NvidiumAPI` are ignored for pack-shaded terrain.
- `gl_ClipDistance`/`gl_CullDistance` written by a pack's vertex shader are not forwarded.
- Changing Nvidium's fog or statistics option needs a shader pack reload (F3+R) before the
  pack programs are used again; until then terrain uses Nvidium's shaders.
- Pinned to Iris 1.11.6 internals. `IrisMixinTargetsTest` fails the build if a hooked
  method changes.

### What to check when testing M2

1. With a pack loaded, F3 shows `Shader pack terrain: pack shaders (N B/vertex)`. If it shows
   `Nvidium shaders (...)`, the reason follows; the log has the full error.
2. Terrain is lit and coloured by the pack, with no gaps, flicker or garbled triangles, also
   while turning quickly (the temporal pass draws newly visible sections).
3. Pack features that use vertex data look right: waving plants (`mc_Entity`, `mc_midTexCoord`),
   normal and parallax maps (`at_tangent`), block-ID-based effects.
4. Try several packs. The log line `built shader pack terrain programs` confirms success; a
   warning about retrying with fewer threads means the pack has large per-vertex outputs.
5. Switching packs and toggling shaders repeatedly doesn't crash or leak.

---

## 1. Why upstream turns itself off

`sodiumCompat/IrisCheck.checkIrisShouldDisable()` returns false whenever
`IrisApi.isShaderPackInUse()` is true. `MixinRenderSectionManager` then leaves
`Nvidium.IS_ENABLED` false, and Sodium's normal renderer draws the terrain.

The reason is that the two programs don't fit together:

| | Nvidium | Iris and the shader pack |
|---|---|---|
| Geometry stage | `GL_NV_mesh_shader` task and mesh shaders (`terrain/task.glsl`, `terrain/mesh.glsl`) | The pack's vertex shader, plus optional geometry and tessellation shaders |
| Vertex input | Reads `terrainData[]` through a bindless pointer | Vertex attributes (`a_Position`, `mc_Entity`, `iris_Normal`, ...) |
| Vertex layout | Its own 16-byte format, or Sodium's 20-byte `COMPACT` format | Iris's "XHFP" layout: 20 bytes plus optional blockId, normal, midUV and midBlock fields |
| Passes | One camera view, with occlusion culling against its own depth | A shadow pass from the sun, then G-buffer passes with many render targets |

## 2. Key findings that make this possible

1. **Iris already rewrites pack shaders for Sodium.**
   `TransformPatcher.patchSodium` calls `SodiumTransformer` (old-style GLSL) or
   `SodiumCoreTransformer` (core profile). These rewrite `gl_Vertex`,
   `gl_MultiTexCoord*`, `mc_Entity`, `mc_midTexCoord`, `at_tangent` and the
   built-in matrices in terms of a small set of inputs:
   `a_Position`, `a_TexCoord`, `a_Color`, `a_LightAndData`, `iris_Normal`,
   `mc_Entity`, `mc_midTexCoord` and `at_midBlock`. An injected `_vert_init()`
   decodes these inputs at the top of `main()`.
   → We **don't** need to re-implement any pack-facing semantics. We take
   Iris's *already patched* Sodium vertex shader and only replace where its
   inputs come from and where its outputs go.

2. **Nvidium can already read Sodium's packed vertex data.**
   `use_sodium_vertex_format` makes Nvidium store Sodium's `COMPACT` vertices,
   and `vertex_format/sodium_vertex_format.glsl` decodes them. Iris's XHFP
   layout is `COMPACT` plus extra 4-byte fields
   (`vertices/sodium/terrain/FormatAnalyzer.java`), so the decoding is the
   same, just with a bigger stride.

3. **`ExtendedShader` does the rest of Iris's per-draw setup for a GL program.**
   For a program ID it handles: pack uniforms, custom uniforms, samplers,
   images, the G-buffer framebuffer bind (before or after translucent), blend
   overrides and the alpha test. None of it depends on the program having a
   vertex stage. `ShaderCreator.create(..., Patch.SODIUM)` →
   `ShaderCreator.link(...)` is the single place where those programs are
   compiled.

4. **The shadow pass already goes through Nvidium's hook.**
   `ShadowRenderer` calls `SodiumWorldRenderer.setupTerrain` with a shadow
   viewport and then renders the opaque layer group. Nvidium's
   `MixinSodiumWorldRenderer.renderLayer` intercepts that call too.
   `IrisApi.isRenderingShadowPass()` tells the two passes apart.

5. **Iris's public API (`api/v0`) doesn't expose pack programs or sources.**
   We'll have to use mixins and accessors into Iris internals, pinned to exact
   Iris versions. Upstream already pins exact Sodium versions the same way.
   Long term, propose an "external terrain renderer" API to Iris (milestone M6).

## 3. Architecture

```
Pack terrain program (gbuffers_terrain / _cutout / water / shadow)
        │  Iris: TransformPatcher.patchSodium   (unchanged)
        ▼
Patched Sodium VS + FS  ──►  NvMeshTransformer (new, uses glsl-transformer)
                                  │  VS  →  mesh shader (pack VS body as a function)
                                  │  FS  →  kept as-is (small fixups only)
                                  ▼
            task.glsl (Nvidium) + generated mesh + pack FS  →  linked GL program
                                  │
                                  ▼
                 ExtendedShader (Iris)  →  uniforms, samplers, framebuffer, blend
                                  │
Nvidium render hook ──► apply() ──► bind Nvidium buffers + Sodium UBOs ──► glMultiDrawMeshTasksIndirectNV
```

### 3.1 Mode switch (`IrisCompat`, replaces `IrisCheck`)

- New config option `iris_support` (default **off** at first, so upstream
  behaviour is the default until the feature is stable).
- When it's on and a pack is active: Nvidium stays enabled, and every pack
  program that translates successfully uses the mesh path.
- **Per-pack fallback:** if any terrain program fails to translate or link, or
  uses geometry or tessellation stages, turn Nvidium off for that pack (today's
  behaviour) and log why.

### 3.2 Vertex data

- In Iris mode, force the Sodium-format path. Take the stride from
  `ChunkMeshFormats.getCurrent().getVertexFormat().getVertexSize()`, not from
  `COMPACT`.
- Code that currently assumes the stride:
  - `MixinChunkBuilderMeshingTask.captureVertexFormat`
  - `SodiumResultCompatibility.copyQuad` (hard-coded `CompactChunkVertex.STRIDE * 4`)
  - The `SectionManager` constructor in `NvidiumWorldRenderer`
  - `MixinRenderSectionManager` (vertex-format override)
- Position decoding (the first 8 bytes) is identical, so the section bounds
  and translucency centroid code still work.
- Rebuild all chunks whenever the format changes: on pack load or unload, or
  when the material map changes.
- In GLSL, define the vertex struct and its stride from the active format, and
  fetch the extra fields (`mc_Entity`, `iris_Normal`, `mc_midTexCoord`,
  `at_midBlock`) at Iris's offsets.

### 3.3 `NvMeshTransformer`: vertex shader → mesh shader

Input: the patched Sodium VS from Iris. Transformations:

1. Rename `main` to `_nv_pack_vertex_main`.
2. **Inputs:** remove the `in` declarations (`a_Position`, `a_TexCoord`,
   `a_Color`, `a_LightAndData`, `iris_Normal`, `mc_Entity`, `mc_midTexCoord`,
   `at_midBlock`) and turn them into globals. Before each call to
   `_nv_pack_vertex_main`, a generated `_nv_load_vertex(addr)` fills them from
   `terrainData` (the Nvidium buffer pointer).
3. **Region and section data:** Iris's `_vert_init` computes the position as
   `_vert_position + iris_RegionOffset + _get_draw_translation(_draw_id)`.
   *As built:* the section index in `a_LightAndData.w` is forced to 0, so
   `_get_draw_translation` returns 0, and `iris_RegionOffset` (now a global) is
   set to the task's `origin` plus Nvidium's camera offset within its chunk.
   Region transforms (`transformationArray`) are not applied yet.
4. **Outputs:** for every `out` varying `T name`:
   - Rename the global to `_nv_vs_name`. The pack body writes to it as usual.
   - Declare a mesh output `out T name[]`, keeping its layout, `flat` and
     interpolation qualifiers, so it links by name to the pack's FS `in T name`.
   - After each call, copy `name[vid] = _nv_vs_name`.
   - *As built:* instead of zeroing every global, the pack's global
     initializers are re-run before each vertex, and `_nv_vs_Position` is reset.
     Globals without initializers are undefined in a vertex shader too.
5. `gl_Position` → `_nv_vs_Position` → `gl_MeshVerticesNV[vid].gl_Position`.
   The **pack's** `gl_Position` is required because packs apply TAA jitter
   and shadow distortion. Also carry over `gl_ClipDistance` if the pack writes
   it.
6. Wrap everything in a modified copy of Nvidium's `terrain/mesh.glsl`. Each
   thread calls the pack function twice: once for the shared corner vertex
   `Vc` and once for its unique vertex `V`. The primitive and index emission
   stays as it is.
7. **Degenerate-triangle culling** must use the pack's clip-space positions.
   Turn it off in shadow passes, where the shadow distortion is non-linear.
8. Prefix every Nvidium identifier with `_nv_` so it can't collide with pack
   code. Keep Nvidium's `SceneData` UBO on a binding Iris doesn't use.

Fragment shader: use Iris's patched FS as-is. Check that nothing in it uses
`gl_PrimitiveID`, which Nvidium uses for its own quad indexing.

Mesh-shader limits to check for each pack:
- Per-workgroup output memory is limited: 64 vertices × the size of the pack's
  varyings. If a pack goes over, generate a version with fewer quads per
  workgroup (`max_vertices=32`, 16 triangle pairs) and adjust the task shader
  to match.
- Extensions the pack declares must be allowed in mesh shaders.
  `#extension` lines are moved to the top.

### 3.4 Program creation hook

Add a mixin on `ShaderCreator.create` for `Patch.SODIUM` keys. Alongside the
normal Sodium program, build an "Nvidium variant":

- Run the transformer.
- Link task, mesh and fragment shaders with Nvidium's `Shader` builder, or
  with a `link` variant that doesn't call `bindAttributesIris`.
- Wrap the result in an `ExtendedShader` with the same uniform, sampler and
  framebuffer setup.

Keep one variant per key in a side table:

| Iris key | Pack program |
|---|---|
| `SODIUM_TERRAIN_SOLID` | `gbuffers_terrain_solid` |
| `SODIUM_TERRAIN_CUTOUT` | `gbuffers_terrain_cutout` |
| `SODIUM_TERRAIN_TRANSLUCENT` | `gbuffers_water` |
| `SHADOW_SODIUM_TERRAIN_*` | `shadow_*` |

Sodium never draws terrain while Nvidium is on, so the normal programs stay
unused but valid. That gives an instant fallback.

### 3.5 Render loop changes (`MixinSodiumWorldRenderer.renderLayer`)

- **Solid and cutout:** upstream draws CUTOUT together with SOLID using one
  fixed alpha cutoff of 0.5. In Iris mode, draw them separately with their own
  pack programs and Iris alpha tests. We can't tell them apart per vertex:
  Iris's layout reuses the byte where Sodium keeps material flags, using it
  for the tangent sign (`packLightAndData` in `XHFPTerrainVertex`). But the
  chunk build output hands over SOLID and CUTOUT as separate mesh lists
  (`SodiumResultCompatibility`, around line 219), which Nvidium currently
  merges per face range. In Iris mode, keep them as separate ranges per
  section, with a split point the task shader can read, and draw each range
  with its own program.
- **Setup before each draw:**
  - Call `ExtendedShader.apply()`; it binds the G-buffer framebuffer.
  - Bind Nvidium's `SceneData` buffer, the command buffer and the
    terrain-data pointer.
  - Provide what the pack shader expects from Sodium: the `u_Globals` UBO,
    `iris_SodiumPushConstants`, and `u_SectionTimeInfo` (for `mc_chunkFade`;
    use a constant of 1.0 at first).
- Leave depth and colour state to Iris. Don't use upstream's hard-coded
  `_colorMask(0, 15)`, `GL_GEQUAL` and blend calls, except where Iris expects
  them (reverse-Z: check `IrisApi.isReverseZDuringShaders()`).
- **Translucent:** Nvidium's sorted translucent pass with the `gbuffers_water`
  variant. Iris's OIT and blend overrides come from `ExtendedShader`.

### 3.6 Shadow pass

- Detect it with `IrisApi.isRenderingShadowPass()`.
- Use frustum-only culling. Nvidium's occlusion culling (region and section
  raster against last frame's depth) is only valid for the player camera.
  Section visibility must come from the shadow frustum, which Sodium already
  computes in `setupTerrain` with the shadow viewport.
- Turn off back-face culling (matches Iris), and turn off the temporal and
  occlusion phases.
- Respect the pack's shadow render distance (`shadowDistance`) and
  `shadowTerrain=false`.

## 4. Risks and open questions

- **Iris internals change between versions.** Pin exact Iris versions in
  `fabric.mod.json`. Keep every Iris touchpoint in one `compat/iris` package.
- **Packs that use geometry or tessellation shaders for terrain** can't run as
  mesh shaders. They get the per-pack fallback (section 3.1).
- **Varying count and size** may push packs past mesh output limits. Handled
  by the smaller-workgroup version (section 3.3), at some performance cost.
- **Correctness details:** `mc_chunkFade`, `at_midBlock`, entity IDs for
  fluids, the face-culling interaction (Iris disables block-face culling in the
  shadow pass), and translucency sort order with pack blend modes.
- **Distant Horizons:** Iris has separate `dh_terrain` programs and
  transformers. Out of scope at first; check that both can run side by side.
- **Licensing:** Nvidium and Iris are both LGPL-3.0. Copying Iris code into
  this fork (rather than calling it) must keep LGPL notices.
- **Mod identity:** decide whether the fork ships as a drop-in `nvidium`
  replacement (same mod ID) or under a new ID that conflicts with upstream.

## 5. Milestones

| # | Milestone | Done when |
|---|---|---|
| M0 | Fork builds | `./gradlew build` passes on JDK 25; CI workflow added; jar loads in a 26.3 instance with Sodium 0.9.2 and Iris 1.11.6 |
| M1 | Iris mode, built-in shaders | Option on, pack active → Nvidium stays enabled and draws terrain from Iris-format vertex data with **Nvidium's own** shaders (no pack effects yet), with no crash or corruption. Proves the data path and the stride changes |
| M2 | Opaque terrain through the pack | `NvMeshTransformer` and the program hook; `gbuffers_terrain*` renders through the generated mesh programs into the G-buffers. First tested with a minimal test pack checked into `testpacks/`, then real packs |
| M3 | Translucent | `gbuffers_water` through Nvidium's sorted translucent pass |
| M4 | Shadows | Shadow pass with frustum-only culling; shadows correct with distortion |
| M5 | Compatibility and performance sweep | Matrix of popular packs: works, falls back, or broken (with reason). FPS compared against Sodium with Iris |
| M6 | Upstreaming | Propose an Iris API for external terrain renderers (program sources plus `ExtendedShader` access); offer the work back to upstream Nvidium |
