import { createTheme, defaultVariantColorsResolver, parseThemeColor, type VariantColorsResolver } from '@mantine/core'

/** Pale fills where white text cannot reach 4.5:1 at any usable shade: these get dark text instead. */
const LIGHT_FILLS = new Set(['yellow', 'lime', 'orange'])

/**
 * Mantine's default badge and button colours miss WCAG 4.5:1 text contrast (light yellow badge 1.7:1, filled red 3.3:1).
 * Light, subtle and outline variants get a darkened shade-9 text; filled variants use shade 9 behind white text,
 * or dark text on the pale fills. Measured in the browser after the change.
 */
const variantColorResolver: VariantColorsResolver = (input) => {
  const d = defaultVariantColorsResolver(input)
  const parsed = parseThemeColor({ color: input.color || input.theme.primaryColor, theme: input.theme })
  if (!parsed.isThemeColor || parsed.shade !== undefined) return d
  const c = parsed.color
  const text = `color-mix(in oklch, var(--mantine-color-${c}-9), black 30%)`
  if (input.variant === 'light' || input.variant === 'subtle' || input.variant === 'outline') return { ...d, color: text }
  if (input.variant === 'filled' || input.variant === undefined) {
    if (LIGHT_FILLS.has(c)) return { ...d, background: `var(--mantine-color-${c}-5)`, hover: `var(--mantine-color-${c}-6)`, color: 'var(--mantine-color-black)' }
    return { ...d, background: `var(--mantine-color-${c}-9)`, hover: `color-mix(in oklch, var(--mantine-color-${c}-9), black 15%)`, color: 'var(--mantine-color-white)' }
  }
  return d
}

/** Lovable design (WorkspaceShell): Mantine blue, Nunito Sans, small default radius. Colours come from index.css tokens. */
export const theme = createTheme({
  primaryColor: 'blue',
  fontFamily: '"Nunito Sans", system-ui, sans-serif',
  defaultRadius: 'sm',
  headings: { fontFamily: '"Nunito Sans", system-ui, sans-serif' },
  // Mantine transitions (modals, tooltips, menus) turn off when the OS asks for reduced motion.
  respectReducedMotion: true,
  variantColorResolver,
})
