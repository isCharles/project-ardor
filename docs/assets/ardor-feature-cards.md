# Ardor feature cards

The repository banner uses [ardor-feature-cards.svg](ardor-feature-cards.svg), a self-contained SVG animation showing six Ardor features:

- Résumé insights
- Mock interviews
- Interview recaps
- Learning and review, including flashcards and calendar plans
- Personal knowledge base
- AI career agent

The cards unfold from a stack, pause in two columns, and return over a 12-second loop. Blue, pink, lilac, and mist ambient color sits on a white background, matching the official Ardor mark; the header tile uses that same four-petal mark. Feature accents stay inside the blue, pink, lilac, and cyan families instead of the old orange brand color.

The SVG contains CSS animations, vector icons, and system-font text. It has no scripts, external images, fonts, or CDN dependencies. The animated file honors `prefers-reduced-motion` when opened directly, and both READMEs also wrap it in a `<picture>` element whose `<source media="(prefers-reduced-motion: reduce)">` selects the [static version](ardor-feature-cards-static.svg). GitHub preserves that source, so reduced-motion readers never start the loop; renderers that do not support SVG animation can use the static file directly.

Edit labels in the SVG text elements. Each feature group has a data-feature attribute; the corresponding unfold-* keyframes control its stack and expanded positions. Both SVGs share the same artwork, with the static version applying the still class to the root element. Regenerate the static copy after editing the animated file:

```sh
node -e 'const fs=require("fs");const p="docs/assets/ardor-feature-cards.svg";fs.writeFileSync(p.replace(".svg","-static.svg"),fs.readFileSync(p,"utf8").replace("<svg xmlns=","<svg class=\"still\" xmlns="))'
```

The motion was inspired by the card-stack interaction in [OmniStack's preview](https://www.aura.build/design-systems/omnistack-global-infrastructure). Ardor's artwork, icons, color treatment, and copy were drawn for this repository; the source template is not included.

Validation (2026-10-30): rendered in headless Chromium directly and through `<img>`; all six cards parsed, the stack unfolded and returned, the static copy showed the expanded state, and reduced motion left the static source unchanged. The branch README preview on GitHub loaded the 1440×900 image from the repository raw path in light and dark mode, animated the cards, and switched to the static source with reduced motion. The merged main README still needs the same visual spot-check after publication.
