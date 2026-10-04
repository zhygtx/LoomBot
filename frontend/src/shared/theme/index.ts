export type ThemeName = 'default'

export function applyTheme(theme: ThemeName): void {
  document.documentElement.dataset.theme = theme
}
