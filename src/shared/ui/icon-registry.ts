import type { Component } from 'vue'

import {
  ArrowUpRight,
  CircleUserRound,
  House,
  KeyRound,
  LayoutDashboard,
  LogOut,
  Menu,
  Network,
  PanelLeftClose,
  PanelLeftOpen,
  RefreshCw,
  Settings,
  ShieldCheck,
  Sparkles,
} from '@lucide/vue'

const ICONS: Record<string, Component> = {
  arrow: ArrowUpRight,
  dashboard: LayoutDashboard,
  home: House,
  key: KeyRound,
  layout: LayoutDashboard,
  logout: LogOut,
  menu: Menu,
  network: Network,
  refresh: RefreshCw,
  settings: Settings,
  shield: ShieldCheck,
  sparkles: Sparkles,
  user: CircleUserRound,
}

export type IconKey = keyof typeof ICONS

export function iconFor(key: string | null | undefined, fallback: IconKey = 'menu'): Component {
  return ICONS[key?.trim().toLowerCase() ?? ''] ?? ICONS[fallback]!
}

export const shellIcons = {
  collapse: PanelLeftClose,
  expand: PanelLeftOpen,
  menu: Menu,
  refresh: RefreshCw,
  logout: LogOut,
}
