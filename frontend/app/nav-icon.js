// Compact navigation marks; labels remain visible at every viewport width.
export default function NavIcon({name}) {
  const paths={
    home:<><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></>,
    practice:<><path d="m8 6-6 6 6 6m8-12 6 6-6 6m-3-14-2 16"/></>,
    diagnostic:<><path d="M9 4H5v17h14V4h-4"/><rect x="9" y="2" width="6" height="4" rx="1"/><path d="m8 12 2 2 6-5m-8 9h8"/></>,
    generation:<><rect x="3" y="3" width="18" height="18" rx="3"/><path d="M12 7v10M7 12h10"/></>,
    mypage:<><circle cx="12" cy="8" r="4"/><path d="M4 22v-3a8 8 0 0 1 16 0v3"/></>,
    training:<><path d="M4 4v16h17M8 15l4-5 4 3 5-8"/></>
  };
  return <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}
