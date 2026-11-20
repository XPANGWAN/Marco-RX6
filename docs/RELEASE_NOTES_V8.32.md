# MARCO RX6 8.32

## Motion and startup experience
- Refine the startup logo animation with a restrained camera push-in while respecting the reduced-motion setting.
- Smooth full-screen navigation transitions without animating the entire library tree.

## Performance
- Precompile parameterized Arabic message patterns instead of allocating regular expressions during repeated translations.
- Cache localized startup stage labels and update status text only when the stage changes.
- Prevent text-size scaling from compounding when screens are re-skinned, while allowing detached views to be collected.

## Localization
- Expand Arabic, English, French, and Spanish coverage for platform navigation, downloads, browser protection messages, storage progress, and community links.
- Add regression tests for newly covered translations and dynamic onboarding labels.

## Compatibility
- Preserve existing Google sign-in, Firebase profiles, Blogger publishing, Google Drive uploads, download engine, library behavior, and current appearance customization.
