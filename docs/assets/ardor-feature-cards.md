# Ardor feature cards

The repository banner uses [ardor-feature-cards.svg](ardor-feature-cards.svg), a self-contained SVG animation showing six Ardor features:

- Résumé insights
- Mock interviews
- Interview recaps
- Learning and review, including flashcards and calendar plans
- Personal knowledge base
- AI career agent

The cards unfold from a stack, pause in two columns, and return over a 12-second loop. Orange, pink, violet, and sage ambient color sits on a white background, following the project's [design direction](../DESIGN.md).

The SVG contains CSS animations, vector icons, and system-font text. It has no scripts, external images, fonts, or CDN dependencies. Reduced-motion preferences show every card without animation. A [static version](ardor-feature-cards-static.svg) is available for renderers that do not support SVG animation.

Edit labels in the SVG text elements. Each feature group has a data-feature attribute; the corresponding unfold-* keyframes control its stack and expanded positions. Both SVGs share the same artwork, with the static version applying the still class to the root element.

The motion was inspired by the card-stack interaction in [OmniStack's preview](https://www.aura.build/design-systems/omnistack-global-infrastructure). Ardor's artwork, icons, color treatment, and copy were drawn for this repository; the source template is not included.

Validation: rendered in headless Edge; all six cards parsed, expanded, and returned to their initial transforms. Reduced-motion behavior and the local preview's pause and expansion controls were checked. GitHub's image proxy and target-platform rendering must be checked separately after publication.
