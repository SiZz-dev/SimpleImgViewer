// Line icons drawn for this project (24×24 grid, 1.8 stroke) — no external icon package or license.
// They size with the surrounding font-size (1em), so the --size-icon-* tokens on the parent decide the size.
const shapes={
  'panel-left':<><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/></>,
  'panel-right':<><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M15 4v16"/></>,
  image:<><rect x="3" y="5" width="18" height="14" rx="2"/><circle cx="9" cy="10" r="1.6"/><path d="M21 16l-5-5-8 8"/></>,
  video:<><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M10 9l5 3-5 3z"/></>,
  audio:<><path d="M9 18V6l10-2v12"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="16.5" cy="16" r="2.5"/></>,
  'zoom-in':<><circle cx="11" cy="11" r="7"/><path d="M8 11h6M11 8v6M21 21l-4.5-4.5"/></>,
  'zoom-out':<><circle cx="11" cy="11" r="7"/><path d="M8 11h6M21 21l-4.5-4.5"/></>,
  fit:<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>,
  'rotate-left':<path d="M4 12a8 8 0 1 0 2.4-5.7M4 4v5h5"/>,
  'rotate-right':<path d="M20 12a8 8 0 1 1-2.4-5.7M20 4v5h-5"/>,
  magnifier:<><circle cx="10" cy="10" r="6"/><circle cx="10" cy="10" r="2.5"/><path d="M20 20l-5.6-5.6"/></>,
  sun:<><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></>,
  moon:<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/>,
  plus:<path d="M12 5v14M5 12h14"/>,
  link:<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>,
  trash:<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6"/>,
  note:<><rect x="4" y="4" width="16" height="16" rx="2"/><path d="M8 9h8M8 13h8M8 17h5"/></>,
  stamp:<path d="M9 3h6v5l3 4H6l3-4zM5 15h14v3H5zM7 21h10"/>,
  crop:<path d="M6 2v14a2 2 0 0 0 2 2h14M18 22V8a2 2 0 0 0-2-2H2"/>,
  upload:<path d="M12 16V4M7 9l5-5 5 5M5 20h14"/>,
  download:<path d="M12 4v11M7 10l5 5 5-5M5 20h14"/>,
  'chevron-left':<path d="M15 6l-6 6 6 6"/>,
  'chevron-right':<path d="M9 6l6 6-6 6"/>,
  move:<path d="M5 3l14 7-6 2-2 6z"/>,
  undo:<path d="M9 14L4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3"/>,
  redo:<path d="M15 14l5-5-5-5M20 9H10a6 6 0 0 0 0 12h3"/>,
  sliders:<><path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/></>,
  loader:<path d="M12 3a9 9 0 1 0 9 9"/>,
  alert:<><circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16.5h.01"/></>,
  check:<><circle cx="12" cy="12" r="9"/><path d="M8 12l3 3 5-6"/></>,
  info:<><circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5h.01"/></>,
  close:<path d="M6 6l12 12M18 6L6 18"/>,
  help:<><circle cx="12" cy="12" r="9"/><path d="M9.5 9.2a2.6 2.6 0 1 1 3.6 2.4c-.7.3-1.1.9-1.1 1.7v.4M12 17h.01"/></>,
};

export const iconNames=Object.keys(shapes);

// Decorative only: the button or text next to it carries the accessible name.
export default function Icon({name,className=''}){
  return <svg className={'icon'+(className?' '+className:'')} viewBox="0 0 24 24" aria-hidden="true" focusable="false" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{shapes[name]}</svg>;
}
