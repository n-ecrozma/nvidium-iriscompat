# Iris shader pack support: design and roadmap

This fork's goal: keep Nvidium's mesh-shader terrain renderer running while an
Iris shader pack is active. Each pack's terrain shaders are translated
automatically into mesh-shader programs, so packs work without hand edits.

- Base: upstream `MCRcortex/nvidium`, branch `26.3` (commit `27ceb72`).
- Minecraft 26.3, Sodium 0.9.2, Iris 1.11.6 (the Iris source was read at
  `IrisShaders/Iris` branch `26.3`, commit `671a5c8`).
- Work branch: `iris-compat`.

Status: **M1 implemented, not yet tested in game** (compiles; needs an NVIDIA GPU with
Minecraft 26.3, Sodium 0.9.2 and Iris 1.11.6 to try).

### What M1 does

- New option **"Iris shader pack support (experimental)"** (`iris_support` in
  `config/nvidium-config.json`, default off). With it on, Nvidium stays enabled while
  a pack is active.
- `TerrainVertexLayout` picks the terrain vertex layout once, when the renderer is
  created. With a pack active, that's Iris's extended format
  (`ChunkMeshFormats.getCurrent()`). Chunk repacking, the GPU geometry arena and the
  shaders all use that choice instead of reading the config. The GLSL `Vertex` struct
  is padded with `VERTEX_EXTRA_WORDS` so it matches the real stride.
- During Iris's shadow pass, Nvidium draws nothing and ignores the shadow viewport.

### Known M1 limitations (expected)

- Terrain is drawn with **Nvidium's own shaders**, not the pack's (that's M2). Where
  it lands depends on which framebuffer Iris has bound, so terrain may look wrong or
  be hidden by the pack's composite passes.
- **No terrain shadows** (M4).
- If the pack enables Iris's separate-AO mode, vertex colours carry AO in alpha, which
  Nvidium's built-in shader ignores. Ambient occlusion goes missing until M2.
- Iris rebuilds the renderer only when a pack change requires it
  (`WorldRenderingSettings.isReloadRequired()`). Otherwise the previous layout stays
  in use until the next reload (F3+A). That's safe, because the chunk builder and
  Nvidium always agree on the layout.

### What to check when testing M1

1. Option off, pack on → Nvidium turns off and Sodium renders the pack (upstream
   behaviour unchanged).
2. Option on, pack on → the log shows
   `Terrain vertex layout: Sodium format, N bytes per vertex (Iris shader pack active)`
   with N > 20 for most packs; no crash, GL errors or garbled geometry; F3 shows
   Nvidium's debug lines.
3. Option on, pack off → `Nvidium format, 16 bytes per vertex` (or Sodium format,
   20 bytes, with "Use sodium vertex format" on), and rendering looks like upstream.
4. Switching packs on and off repeatedly doesn't crash or leak.

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
   Make `_get_draw_translation` return Nvidium's per-section `origin` (from the
   task payload) and set `iris_RegionOffset` to 0. Check this against
   Nvidium's camera-relative convention and `transformationArray`.
4. **Outputs:** for every `out` varying `T name`:
   - Rename the global to `_nv_vs_name`. The pack body writes to it as usual.
   - Declare a mesh output `out T name[]`, keeping its layout, `flat` and
     interpolation qualifiers, so it links by name to the pack's FS `in T name`.
   - After each call, copy `name[vid] = _nv_vs_name`.
   - Reset the globals to zero before each call, so values from one vertex
     can't leak into the next.
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
