import { createTheme } from '@mantine/core'

/** Lovable design (WorkspaceShell): Mantine blue, Nunito Sans, small default radius. Colours come from index.css tokens. */
export const theme = createTheme({
  primaryColor: 'blue',
  fontFamily: '"Nunito Sans", system-ui, sans-serif',
  defaultRadius: 'sm',
  headings: { fontFamily: '"Nunito Sans", system-ui, sans-serif' },
})
