# Ardor brand assets

The supplied vectors in this directory are the source of truth. Do not redraw,
recolour or re-export them by hand.

| File | Role |
| --- | --- |
| `ardor-mark.svg` | Official mark, supplied 2026-10-30, unmodified |
| `ardor-app-light.svg` | Official light app icon, unmodified |
| `ardor-app-dark.svg` | Official dark app icon, unmodified |
| `ardor-mark-favicon.svg` | Optical 16–48 px cut of `ardor-mark.svg`; same paths, tighter viewBox, glow layers omitted, stronger gradient stops |
| `ardor-app-small.svg` | Optical 16–32 px cut of `ardor-app-light.svg`; same tile and paths, full-bleed viewBox, glow/fold omitted, mark enlarged 6% |
| `preview-light.png` / `preview-dark.png` | Generated contact sheets |

Regenerate every raster asset and both preview sheets:

```sh
cd desktop
npm run icons
```

`npm run icons:check` verifies the committed web icons and `desktop/assets/icon.svg`
against these sources without writing anything. It runs in the Windows installer
workflow before the release is built, and `frontend/tests/brand-ui.mjs` re-checks
the committed SVGs on every frontend PR.
Desktop and taskbar icons use the transparent `ardor-mark-favicon.svg` directly;
the app tiles remain available for opaque surfaces such as the iOS touch icon.
`desktop/assets/icon.png` and `icon.ico` are build outputs and stay gitignored.
