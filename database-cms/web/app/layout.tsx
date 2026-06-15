import type { Metadata } from "next";
import "./styles.css";
import "./cms-shell.css";

export const metadata: Metadata = {
  title: "Cosmic Database CMS",
  description: "Visual administration for Cosmic MapleStory",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
