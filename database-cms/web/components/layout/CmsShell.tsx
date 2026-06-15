"use client";

import { CircleUserRound, LucideIcon } from "lucide-react";
import { ReactNode } from "react";

export type CmsNavItem<T extends string> = {
  key: T;
  label: string;
  icon: LucideIcon;
};

type CmsShellProps<T extends string> = {
  activeView: T;
  brandMark: string;
  brandSubtitle: string;
  eyebrow: string;
  headerStatus: string;
  inspectorOpen?: boolean;
  inspectorSize?: "standard" | "wide";
  navigation: readonly CmsNavItem<T>[];
  onNavigate: (view: T) => void;
  sidebarStatus: ReactNode;
  children: ReactNode;
  inspector?: ReactNode;
};

export function CmsShell<T extends string>({
  activeView,
  brandMark,
  brandSubtitle,
  eyebrow,
  headerStatus,
  inspectorOpen = false,
  inspectorSize = "standard",
  navigation,
  onNavigate,
  sidebarStatus,
  children,
  inspector,
}: CmsShellProps<T>) {
  const current = navigation.find((item) => item.key === activeView);

  return (
    <div className={`cms-shell${inspectorOpen ? ` has-inspector inspector-${inspectorSize}` : ""}`}>
      <aside className="cms-sidebar">
        <div className="cms-brand">
          <div className="cms-brand-mark">{brandMark}</div>
          <div>
            <strong>Cosmic</strong>
            <small>{brandSubtitle}</small>
          </div>
        </div>
        <nav className="cms-navigation" aria-label={`${brandSubtitle} navigation`}>
          {navigation.map(({ key, label, icon: Icon }) => (
            <button
              type="button"
              className={activeView === key ? "active" : ""}
              key={key}
              onClick={() => onNavigate(key)}
            >
              <Icon size={18} />
              <span>{label}</span>
            </button>
          ))}
        </nav>
        <div className="cms-sidebar-status">{sidebarStatus}</div>
      </aside>
      <main className="cms-main">
        <header className="cms-header">
          <div>
            <p className="eyebrow">{eyebrow}</p>
            <h1>{current?.label}</h1>
          </div>
          <div className="cms-header-actions">
            <span className="cms-status-pill">{headerStatus}</span>
            <CircleUserRound size={30} />
          </div>
        </header>
        <section className="workspace">{children}</section>
      </main>
      {inspector}
    </div>
  );
}
