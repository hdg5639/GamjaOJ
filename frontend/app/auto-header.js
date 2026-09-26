'use client';

// Account controls stay in one predictable place instead of appearing on scroll.
export default function AppHeader({children}) {
  return <div className="header-slot"><header id="global-header">{children}</header></div>;
}
