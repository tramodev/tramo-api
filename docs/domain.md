# Core concepts

## Entity relationship diagram

[View diagram](tramo-backend.png) · [Editable source](tramo-backend.drawio)

## Domain model

The core domain lives in the `trail` package and is built on two layers.

**The graph** — how ideas relate through references in note content:

- **Item** — an atomic unit of content (title + rich-text body in `ItemContent`).
  An item can appear in many trails at once (transclusion): it is referenced, not
  copied.
- **Reference** — a note can mention another note with `@` in its rich-text body.
  These references appear as connections in the map.

**The trail** — an independent reading order:

- **Trail** — a named, ordered sequence of notes, versioned and forkable.
- **TrailItem** — membership of a note in a trail, with its `orderIndex`.
  Notes reused in several trails retain the same content and references.

A **Project** groups trails and loose items and is the unit of sharing and forking:
publishing snapshots the project to the public explore feed; forking copies its
items and trails into the forker's own space so later edits to the
original never mutate the fork.
