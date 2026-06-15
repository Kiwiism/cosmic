"use client";

import { Search } from "lucide-react";
import { ReactNode } from "react";

type PageToolbarProps = {
  query: string;
  onQueryChange: (value: string) => void;
  placeholder: string;
  children?: ReactNode;
};

export function PageToolbar({
  query,
  onQueryChange,
  placeholder,
  children,
}: PageToolbarProps) {
  return (
    <div className="cms-toolbar">
      <label className="cms-search">
        <Search size={18} />
        <input
          value={query}
          onChange={(event) => onQueryChange(event.target.value)}
          placeholder={placeholder}
        />
      </label>
      {children}
    </div>
  );
}
