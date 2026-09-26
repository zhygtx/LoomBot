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
  SlidersHorizontal,
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
  sliders: SlidersHorizontal,
  sparkles: Sparkles,
  user: CircleUserRound,
}

export type IconKey = keyof typeof ICONS

/**
 * 注册表里全部可用的图标 key。
 *
 * <p>菜单管理的图标选择器从**这里**取选项，而不是自己维护一份清单 —— 之前页面里硬编码了一份 6 项的
 * datalist，注册表涨到 13 项后两边就对不上了，写错的 key 还会静默回落到默认图标。
 */
export const iconKeys = Object.keys(ICONS) as IconKey[]

/** 图标 key 的中文名，只用于选择器的标题与无障碍标签。 */
export const iconLabels: Record<IconKey, string> = {
  arrow: '外链',
  dashboard: '仪表盘',
  home: '首页',
  key: '密钥',
  layout: '布局',
  logout: '退出',
  menu: '菜单',
  network: '网络',
  refresh: '刷新',
  settings: '设置',
  shield: '权限',
  sliders: '配置项',
  sparkles: '特性',
  user: '用户',
}

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
