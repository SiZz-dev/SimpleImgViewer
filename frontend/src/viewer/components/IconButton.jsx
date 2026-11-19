export default function IconButton({label,children,onClick,disabled=false,active=false}){
  return <button type="button" title={label} aria-label={label} className={'icon-button'+(active?' active':'')} disabled={disabled} onClick={onClick}>{children}</button>;
}
