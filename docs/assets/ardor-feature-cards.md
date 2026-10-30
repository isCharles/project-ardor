# Ardor feature cards

The repository banners use a pair of self-contained SVGs: [English](ardor-feature-cards.svg) and [简体中文](ardor-feature-cards-zh-CN.svg). Each shows six Ardor features:

- Résumé insights
- Mock interviews
- Interview recaps
- Learning and review, including flashcards and calendar plans
- Personal knowledge base
- AI career agent

Both versions keep the same three-level composition: the Ardor wordmark, the headline, and one slogan. The cards contain only an icon, a title, and two short lines; there are no eyebrow labels, tag rows, or footer microcopy.

The cards unfold from a stack, pause in two columns, and return over a 12-second loop. Blue, pink, lilac, and mist ambient color sits on a white background, matching the official Ardor mark; the header tile uses that same four-petal mark. Feature accents stay inside the blue, pink, lilac, and cyan families instead of the old orange brand color.

Each language has an animated file and a static file for reduced motion:

| Language | Animated | Static |
| :--- | :--- | :--- |
| English | `ardor-feature-cards.svg` | `ardor-feature-cards-static.svg` |
| 简体中文 | `ardor-feature-cards-zh-CN.svg` | `ardor-feature-cards-zh-CN-static.svg` |

The SVGs contain CSS animations, vector icons, and system-font text. They have no scripts, external images, fonts, or CDN dependencies. The animated files honor `prefers-reduced-motion` when opened directly, and both READMEs wrap them in a `<picture>` element whose `<source media="(prefers-reduced-motion: reduce)">` selects the static version. GitHub preserves that source, so reduced-motion readers never start the loop; renderers that do not support SVG animation can use the static file directly.

Edit labels in the SVG text elements. Each feature group has a data-feature attribute; the corresponding unfold-* keyframes control its stack and expanded positions. The Chinese file mirrors the English layout; update both when changing geometry or animation. Regenerate both static copies after editing either animated file:

```sh
node -e 'const fs=require("fs");for(const p of ["docs/assets/ardor-feature-cards.svg","docs/assets/ardor-feature-cards-zh-CN.svg"])fs.writeFileSync(p.replace(".svg","-static.svg"),fs.readFileSync(p,"utf8").replace("<svg xmlns=","<svg class=\"still\" xmlns="))'
```

The motion was inspired by the card-stack interaction in [OmniStack's preview](https://www.aura.build/design-systems/omnistack-global-infrastructure). Ardor's artwork, icons, color treatment, and copy were drawn for this repository; the source template is not included.

Validation (2026-10-30): rendered in headless Chromium directly and through `<img>`; all six cards parsed, the stack unfolded and returned, the static copy showed the expanded state, and reduced motion left the static source unchanged. The simplified banners were rendered in English and Chinese at 1440×900 and at an 858 px README width; `prefers-reduced-motion` selected the static file for the English banner. The merged main README still needs the same visual spot-check after publication.
